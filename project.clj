(defproject clj-facebook-graph "0.4.0"
  :description "A Clojure client for the Facebook Graph API."
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.json "0.2.4"]
                 [ring/ring-core "1.2.2"]
                 [ring/ring-devel "1.2.2"]
                 [ring/ring-jetty-adapter "1.0.0"]
                 [ring/ring-jetty-adapter "1.0.0"]
                 [clj-http "0.9.1"]
                 [uri "1.1.0"]
                 [compojure "1.1.6"]
                 [clj-oauth2 "0.2.0"]]
  :dev-dependencies [[ring/ring-devel "1.2.2"]
                     [ring/ring-jetty-adapter "1.0.0"]
                     [compojure "1.1.6"]]
  :plugins [[lein-ring "0.8.7"]]
  :ring {:handler clj-facebook-graph.example/the-app}
  :aot [clj-facebook-graph.FacebookGraphException])
