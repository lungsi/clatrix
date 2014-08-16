(defproject clatrix/clatrix "0.4.1-SNAPSHOT"
  :description "Because using matrices in Clojure needs to not suck."
  :url "http://tel.github.com/clatrix"
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/MIT"}
  :resource-paths ["native"]
  :plugins [[lein-expectations "0.0.8"]]

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [slingshot "0.10.3"]
                 [org.jblas/jblas "1.2.3"]
                 [net.mikera/core.matrix "0.29.1"]]
  
  :profiles {:dev {:dependencies [[criterium/criterium "0.4.3"]
                                  [expectations "1.4.56"]]}})
