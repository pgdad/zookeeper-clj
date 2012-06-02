(defproject org.clojars.pgdad/zookeeper-clj "0.9.3"
  :description "A Clojure DSL for Apache ZooKeeper"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.apache.zookeeper/zookeeper "3.4.3"]
                 [log4j/log4j "1.2.17"]
                 [commons-codec "1.6"]]
  :warn-on-reflection true
  :aot :all
  :dev-dependencies [[lein-clojars "0.9.0"]])
