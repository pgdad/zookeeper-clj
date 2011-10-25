(ns zookeeper
  "
  The core functions of ZooKeeper are name service,
  configuration, and group membership, and this
  functionality is provided by this library.

  See examples:

  * http://developer.yahoo.com/blogs/hadoop/posts/2009/05/using_zookeeper_to_tame_system/
  * http://archive.cloudera.com/cdh/3/zookeeper/zookeeperProgrammers.pdf

"
  (:import (org.apache.zookeeper ZooKeeper
                                 ZooKeeper$States
                                 CreateMode
                                 Watcher
                                 ZooDefs$Ids
                                 ZooDefs$Perms
                                 AsyncCallback$StringCallback
                                 AsyncCallback$VoidCallback
                                 AsyncCallback$StatCallback
                                 AsyncCallback$StatCallback
                                 AsyncCallback$Children2Callback
                                 AsyncCallback$DataCallback
                                 AsyncCallback$ACLCallback
                                 Watcher$Event$KeeperState
                                 Watcher$Event$EventType
                                 KeeperException)
           (org.apache.zookeeper.data Stat
                                      Id
                                      ACL)
           (java.util.concurrent CountDownLatch))
  (:require [clojure.string :as s]
            [zookeeper.logger :as log])
  (:use [zookeeper.internal :only [try*]]))

(def ^:dynamic *perms* {:write ZooDefs$Perms/WRITE
                        :read ZooDefs$Perms/READ
                        :delete ZooDefs$Perms/DELETE
                        :create ZooDefs$Perms/CREATE
                        :admin ZooDefs$Perms/ADMIN})

(defn perm-or
  "
  Examples:

    (use 'zookeeper)
    (perm-or *perms* :read :write :create)
"
  ([perms & perm-keys]
     (apply bit-or (vals (select-keys perms perm-keys)))))

(def acls {:open-acl-unsafe ZooDefs$Ids/OPEN_ACL_UNSAFE ;; This is a completely open ACL
          :anyone-id-unsafe ZooDefs$Ids/ANYONE_ID_UNSAFE ;; This Id represents anyone
          :auth-ids ZooDefs$Ids/AUTH_IDS ;; This Id is only usable to set ACLs
          :creator-all-acl ZooDefs$Ids/CREATOR_ALL_ACL ;; This ACL gives the creators authentication id's all permissions
          :read-all-acl ZooDefs$Ids/READ_ACL_UNSAFE ;; This ACL gives the world the ability to read
          })

(def create-modes { ;; The znode will not be automatically deleted upon client's disconnect
                   {:persistent? true, :sequential? false} CreateMode/PERSISTENT
                   ;; The znode will be deleted upon the client's disconnect, and its name will be appended with a monotonically increasing number
                   {:persistent? false, :sequential? true} CreateMode/EPHEMERAL_SEQUENTIAL
                   ;; The znode will be deleted upon the client's disconnect
                   {:persistent? false, :sequential? false} CreateMode/EPHEMERAL
                   ;; The znode will not be automatically deleted upon client's disconnect, and its name will be appended with a monotonically increasing number
                   {:persistent? true, :sequential? true} CreateMode/PERSISTENT_SEQUENTIAL})

(defn stat-to-map
  ([stat]
     ;;(long czxid, long mzxid, long ctime, long mtime, int version, int cversion, int aversion, long ephemeralOwner, int dataLength, int numChildren, long pzxid)
     (when stat
       {:czxid (.getCzxid stat)
        :mzxid (.getMzxid stat)
        :ctime (.getCtime stat)
        :mtime (.getMtime stat)
        :version (.getVersion stat)
        :cversion (.getCversion stat)
        :aversion (.getAversion stat)
        :ephemeralOwner (.getEphemeralOwner stat)
        :dataLength (.getDataLength stat)
        :numChildren (.getNumChildren stat)
        :pzxid (.getPzxid stat)})))

(defn event-to-map
  ([event]
     (when event
       {:event-type (keyword (.name (.getType event)))
        :keeper-state (keyword (.name (.getState event)))
        :path (.getPath event)})))

(defn event-types
  ":NodeDeleted :NodeDataChanged :NodeCreated :NodeChildrenChanged :None"
  ([] (into #{} (map #(keyword (.name %)) (Watcher$Event$EventType/values)))))

(defn keeper-states
  ":AuthFailed :Unknown :SyncConnected :Disconnected :Expired :NoSyncConnected"
  ([] (into #{} (map #(keyword (.name %)) (Watcher$Event$KeeperState/values)))))

(defn client-states
  ":AUTH_FAILED :CLOSED :CONNECTED :ASSOCIATING :CONNECTING"
  ([] (into #{} (map #(keyword (.toString %)) (ZooKeeper$States/values)))))

;; Watcher


(defn make-watcher
  ([handler]
     (reify Watcher
       (process [this event]
         (handler (event-to-map event))))))

;; Callbacks

(defn string-callback
  ([handler]
     (reify AsyncCallback$StringCallback
       (processResult [this return-code path context name]
         (handler {:return-code return-code
                   :path path
                   :context context
                   :name name})))))

(defn stat-callback
  ([handler]
     (reify AsyncCallback$StatCallback
       (processResult [this return-code path context stat]
         (handler {:return-code return-code
                   :path path
                   :context context
                   :stat (stat-to-map stat)})))))

(defn children-callback
  ([handler]
     (reify AsyncCallback$Children2Callback
       (processResult [this return-code path context children stat]
         (handler {:return-code return-code
                   :path path
                   :context context
                   :children (seq children)
                   :stat (stat-to-map stat)})))))

(defn void-callback
  ([handler]
     (reify AsyncCallback$VoidCallback
       (processResult [this return-code path context]
         (handler {:return-code return-code
                   :path path
                   :context context})))))

(defn data-callback
  ([handler]
     (reify AsyncCallback$DataCallback
       (processResult [this return-code path context data stat]
         (handler {:return-code return-code
                   :path path
                   :context context
                   :data data
                   :stat (stat-to-map stat)})))))

(defn acl-callback
  ([handler]
     (reify AsyncCallback$ACLCallback
       (processResult [this return-code path context acl stat]
         (handler {:return-code return-code
                   :path path
                   :context context
                   :acl (seq acl)
                   :stat (stat-to-map stat)})))))

(defn promise-callback
  ([prom callback-fn]
     (fn [{:keys [return-code path context name] :as result}]
       (deliver prom result)
       (when callback-fn
         (callback-fn result)))))

;; Public DSL

(defn connect
  "Returns a ZooKeeper client."
  ([connection-string & {:keys [timeout-msec watcher]
                         :or {timeout-msec 5000}}]
     (let [latch (CountDownLatch. 1)
           session-watcher (make-watcher (fn [event]
                                           (when (= (:keeper-state event) :SyncConnected)
                                             (.countDown latch))
                                           (when watcher (watcher event))))
           zk (ZooKeeper. connection-string timeout-msec session-watcher)]
       (.await latch)
       zk)))

(defn register-watcher
  ([client watcher]
     (.register client (make-watcher watcher))))

(defn state
  "Returns current state of client, including :CONNECTING, :ASSOCIATING, :CONNECTED, :CLOSED, or :AUTH_FAILED"
  ([client]
     (keyword (.toString (.getState client)))))

(defn exists
  "
  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :wacher #(println \"event received: \" %)))

    (defn callback [result]
      (println \"got callback result: \" result))

    (exists client \"/yadda\" :watch? true)
    (create client \"/yadda\")
    (exists client \"/yadda\")
    (def p0 (exists client \"/yadda\" :async? true))
    @p0
    (def p1 (exists client \"/yadda\" :callback callback))
    @p1
"
  ([client path & {:keys [watcher watch? async? callback context]
                   :or {watch? false
                        async? false
                        context path}}]
     (if (or async? callback)
       (let [prom (promise)]
         (try*
          (.exists client path (if watcher (make-watcher watcher) watch?)
                   (stat-callback (promise-callback prom callback)) context)
          (catch KeeperException e
            (do
              (log/debug (str "exists: KeeperException Thrown: code: " (.code e) ", exception: " e))
              (throw e))))
         prom)
       (try*
        (stat-to-map (.exists client path (if watcher (make-watcher watcher) watch?)))
        (catch KeeperException e
          (do
            (log/debug (str "exists: KeeperException Thrown: code: " (.code e) ", exception: " e))
            (throw e)))))))

(defn create
  " Creates a node, returning either the node's name, or a promise with a result map if the done asynchronously. If an error occurs, create will return false.

  Options:

    :persistent? indicates if the node should be persistent
    :sequential? indicates if the node should be sequential
    :data data to associate with the node
    :acl access control, see the acls map
    :async? indicates that the create should occur asynchronously, a promise will be returned
    :callback indicates that the create should occur asynchronously and that this function should be called when it does, a promise will also be returned


  Example:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watcher #(println \"event received: \" %)))

    (defn callback [result]
      (println \"got callback result: \" result))

    ;; first delete the baz node if it exists
    (delete-all client \"/baz\")
    ;; now create a persistent parent node, /baz, and two child nodes
    (def p0 (create client \"/baz\" :callback callback :persistent? true))
    @p0
    (def p1 (create client \"/baz/1\" :callback callback))
    @p1
    (def p2 (create client \"/baz/2-\" :async? true :sequential? true))
    @p2
    (create client \"/baz/3\")

"
  ([client path & {:keys [data acl persistent? sequential? context callback async?]
                   :or {persistent? false
                        sequential? false
                        acl (acls :open-acl-unsafe)
                        context path
                        async? false}}]
     (if (or async? callback)
       (let [prom (promise)]
         (try*
           (.create client path data acl
                    (create-modes {:persistent? persistent?, :sequential? sequential?})
                    (string-callback (promise-callback prom callback))
                    context)
           (catch KeeperException e
             (do
               (log/debug (str "create: KeeperException Thrown: code: " (.code e) ", exception: " e))
               (throw e))))
         prom)
       (try*
         (.create client path data acl
                  (create-modes {:persistent? persistent?, :sequential? sequential?}))
         (catch org.apache.zookeeper.KeeperException$NodeExistsException e
           (log/debug (str "Tried to create an existing node: " path))
           false)
         (catch KeeperException e
           (do
             (log/debug (str "create: KeeperException Thrown: code: " (.code e) ", exception: " e))
             (throw e)))))))

(defn delete
  "Deletes the given node, if it exists

  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watch #(println \"event received: \" %)))

    (defn callback [result]
      (println \"got callback result: \" result))

    (create client \"/foo\" :persistent? true)
    (create client \"/bar\" :persistent? true)

    (delete client \"/foo\")
    (def p0 (delete client \"/bar\" :callback callback))
    @p0
"
  ([client path & {:keys [version async? callback context]
                   :or {version -1
                        async? false
                        context path}}]
     (if (or async? callback)
       (let [prom (promise)]
         (try*
           (.delete client path version (void-callback (promise-callback prom callback)) context)
           (catch KeeperException e
             (do
               (log/debug (str "delete: KeeperException Thrown: code: " (.code e) ", exception: " e))
               (throw e))))
         prom)
       (try*
         (do
           (.delete client path version)
           true)
         (catch org.apache.zookeeper.KeeperException$NoNodeException e
           (log/debug (str "Tried to delete a non-existent node: " path))
           false)
         (catch KeeperException e
           (do
             (log/debug (str "delete: KeeperException Thrown: code: " (.code e) ", exception: " e))
             (throw e)))))))

(defn children
  "
  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watcher #(println \"event received: \" %)))

    (defn callback [result]
      (println \"got callback result: \" result))

    (delete-all client \"/foo\")
    (create client \"/foo\" :persistent? true)
    (repeatedly 5 #(create client \"/foo/child-\" :sequential? true))

    (children client \"/foo\")
    (def p0 (children client \"/foo\" :async? true))
    @p0
    (def p1 (children client \"/foo\" :callback callback))
    @p1
    (def p2 (children client \"/foo\" :async? true :watch? true))
    @p2
    (def p3 (children client \"/foo\" :async? true :watcher #(println \"watched event: \" %)))
    @p3

"
  ([client path & {:keys [watcher watch? async? callback context]
                   :or {watch? false
                        async? false
                        context path}}]
     (if (or async? callback)
       (let [prom (promise)]
         (try*
           (seq (.getChildren client path
                              (if watcher (make-watcher watcher) watch?)
                              (children-callback (promise-callback prom callback)) context))
           (catch KeeperException e
             (do
               (log/debug (str "children: KeeperException Thrown: code: " (.code e) ", exception: " e  ":= " (.printStackTrace e)))
               (throw e))))
         prom)
       (try*
        (seq (.getChildren client path (if watcher (make-watcher watcher) watch?)))
        (catch org.apache.zookeeper.KeeperException$NoNodeException e
          (log/debug (str "Tried to list children of a non-existent node: " path))
          false)
        (catch KeeperException e
          (do
            (log/debug (str "children: KeeperException Thrown: code: " (.code e) ", exception: " e  ":= " (.printStackTrace e)))
            (throw e)))))))

(defn delete-all
  "Deletes a node and all of its children."
  ([client path & options]
     (doseq [child (or (children client path) nil)]
       (apply delete-all client (str path "/" child) options))
     (apply delete client path options)))

(defn create-all
  "Create a node and all of its parents. The last node will be ephemeral,
   and its parents will be persistent. Option, like :persistent? :sequential?,
   :acl, will only be applied to the last child node.

  Examples:
  (delete-all client \"/foo\")
  (create-all client \"/foo/bar/baz\" :persistent? true)
  (create-all client \"/foo/bar/baz/n-\" :sequential? true)


"
  ([client path & options]
     (loop [parent "" [child & children] (rest (s/split path #"/"))]
       (if child
         (let [node (str parent "/" child)]
           (if (exists client node)
             (recur node children)
             (recur (if (seq children)
                      (create client node :persistent? true)
                      (apply create client node options))
                    children)))
         parent))))

(defn data
  "Returns byte array of data from given node.

  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watcher #(println \"event received: \" %)))

    (defn callback [result]
      (println \"got callback result: \" result))

    (delete-all client \"/foo\")
    (create client \"/foo\" :persistent? true :data (.getBytes \"Hello World\"))
    (def result (data client \"/foo\"))
    (String. (:data result))
    (:stat result)

    (def p0 (data client \"/foo\" :async? true))
    @p0
    (String. (:data @p0))

    (def p1 (data client \"/foo\" :watch? true :callback callback))
    @p1
    (String. (:data @p1))

    (create client \"/foobar\" :persistent? true :data (.getBytes (pr-str {:a 1, :b 2, :c 3})))
    (read-string (String. (:data (data client \"/foobar\"))))

"
  ([client path & {:keys [watcher watch? async? callback context]
                   :or {watch? false
                        async? false
                        context path}}]
     (let [stat (Stat.)]
       (if (or async? callback)
        (let [prom (promise)]
          (try*
           (.getData client path (if watcher (make-watcher watcher) watch?)
                     (data-callback (promise-callback prom callback)) context)
           (catch KeeperException e
             (do
               (log/debug (str "data: KeeperException Thrown: code: " (.code e) ", exception: " e))
               (throw e))))
          prom)
        {:data (try*
                (.getData client path (if watcher (make-watcher watcher) watch?) stat)
                (catch KeeperException e
                  (do
                    (log/debug (str "data: KeeperException Thrown: code: " (.code e) ", exception: " e))
                    (throw e))))
         :stat (stat-to-map stat)}))))

(defn set-data
  "

  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watcher #(println \"event received: \" %)))

    (defn callback [result]
      (println \"got callback result: \" result))

    (delete-all client \"/foo\")
    (create client \"/foo\" :persistent? true)

    (set-data client \"/foo\" (.getBytes \"Hello World\") 0)
    (String. (:data (data client \"/foo\")))


    (def p0 (set-data client \"/foo\" (.getBytes \"New Data\") 0 :async? true))
    @p0
    (String. (:data (data client \"/foo\")))

    (def p1 (set-data client \"/foo\" (.getBytes \"Even Newer Data\") 1 :callback callback))
    @p1
    (String. (:data (data client \"/foo\")))

"
  ([client path data version & {:keys [async? callback context]
                                :or {async? false
                                     context path}}]
     (if (or async? callback)
       (let [prom (promise)]
         (try*
           (.setData client path data version
                     (stat-callback (promise-callback prom callback)) context)
           (catch KeeperException e
             (do
               (log/debug (str "set-data: KeeperException Thrown: code: " (.code e) ", exception: " e))
               (throw e))))
         prom)
       (try*
         (.setData client path data version)
         (catch KeeperException e
           (do
             (log/debug (str "set-data: KeeperException Thrown: code: " (.code e) ", exception: " e))
             (throw e)))))))


;; ACL

(defn get-acl
 "
  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watcher #(println \"event received: \" %)))
    (add-auth-info client \"digest\" \"david:secret\")

    (defn callback [result]
      (println \"got callback result: \" result))

    (delete-all client \"/foo\")
    (create client \"/foo\" :acl [(acl \"auth\" \"\" :read :write :create :delete)])
    (get-acl client \"/foo\")

    (def p0 (get-acl client \"/foo\" :async? true))

    (def p1 (get-acl client \"/foo\" :callback callback))

"
  ([client path & {:keys [async? callback context]
                   :or {async? false
                        context path}}]
     (let [stat (Stat.)]
       (if (or async? callback)
         (let [prom (promise)]
           (try*
             (.getACL client path stat (acl-callback (promise-callback prom callback)) context)
             (catch KeeperException e
               (do
                 (log/debug (str "get-acl: KeeperException Thrown: code: " (.code e) ", exception: " e))
                 (throw e))))
         prom)
         {:acl (try*
                (seq (.getACL client path stat))
                (catch KeeperException e
                  (do
                    (log/debug (str "get-acl: KeeperException Thrown: code: " (.code e) ", exception: " e))
                    (throw e))))
          :stat (stat-to-map stat)}))))

(defn add-auth-info
  "Add auth info to connection."
  ([client scheme auth]
     (try*
      (.addAuthInfo client scheme (if (string? auth) (.getBytes auth) auth))
      (catch KeeperException e
        (do
          (log/debug (str "add-auth-info: KeeperException Thrown: code: " (.code e) ", exception: " e))
          (throw e))))))

(defn acl-id
  ([scheme id-value]
     (Id. scheme id-value)))

(defn acl
  "
  Examples:

    (use 'zookeeper)
    (def client (connect \"127.0.0.1:2181\" :watcher #(println \"event received: \" %)))

    (def open-acl-unsafe (acl \"world\" \"anyone\" :read :create :delete :admin :write))
    (create client \"/mynode\" :acl [open-acl-unsafe])

    (def ip-acl (acl \"ip\" \"127.0.0.1\" :read :create :delete :admin :write))
    (create client \"/mynode2\" :acl [ip-acl])

    (add-auth-info client \"digest\" \"david:secret\")

    ;; works
    ;; same as (acls :creator-all-acl)
    (def auth-acl (acl \"auth\" \"\" :read :create :delete :admin :write))
    (create client \"/mynode4\" :acl [auth-acl])
    (data client \"/mynode4\")

    ;; change auth-info
    (add-auth-info client \"digest\" \"edgar:secret\")
    (data client \"/mynode4\")

"
  ([scheme id-value perm & more-perms]
     (ACL. (apply perm-or *perms* perm more-perms) (acl-id scheme id-value))))





