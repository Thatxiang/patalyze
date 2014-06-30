(ns patalyze.core
  (:require [clojure.tools.nrepl.server :as nrepl]
            [patalyze.index             :as index]
            [liberator.core             :refer  [resource defresource]]
            [ring.middleware.params     :refer  [wrap-params]]
            [ring.middleware.reload     :refer  [wrap-reload]]
            [ring.adapter.jetty         :refer  [run-jetty]]
            [compojure.core             :refer  [defroutes ANY]]
            [environ.core               :refer  [env]]
            [taoensso.timbre            :as timbre :refer [log  trace  debug  info  warn  error]])
  (:gen-class))


(defn simplify-maps [ms]
  (map
    (fn [m]
      (update-in m [:inventors] #(clojure.string/join ", " %)))
    ms))

(defresource apple [pub-date]
  :available-media-types  ["text/csv" "application/json"]
  :handle-ok  (fn  [_] (simplify-maps (index/patents-by-org-and-date "Apple Inc." pub-date))))

(defresource stats []
  :available-media-types ["application/json"]
  :handle-ok (fn [_] (index/patent-count)))

(defroutes app
  (ANY "/apple/:pub-date" [pub-date] (apple pub-date))
  (ANY "/stats" [] (stats)))

(def handler 
  (-> app 
      (wrap-reload)
      (wrap-params))) 

(defn -main
  "The application's main function"
  [& _]
  (timbre/set-config! [:appenders :spit :enabled?] true)
  (timbre/set-config! [:shared-appender-config :spit-filename] (str (env :data-dir) "/patalyze.log"))

  (nrepl/start-server :bind "0.0.0.0" :port 42042)
  (run-jetty #'handler {:port 3000 :join? false})
  (println "nREPL Server started on port 42042")) ; :handler (default-handler lighttable-ops)))
