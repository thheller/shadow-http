(ns shadow.assist.server
  (:require
    [ring.adapter.jetty :as jetty]
    [shadow.runtime.services :as rt]))

(defn handler [req]
  {:status 200
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body "Yo."})

(defn start []
  (let [app-def
        {:dummy
         {:start (fn [] (prn :started-dummy4) :yo)
          :stop (fn [x] (prn :stopped-dummy4))}}

        app-instance
        (-> {::started (System/currentTimeMillis)}
            (rt/init app-def)
            (rt/start-all))

        ext-def
        {:http
         {:start
          (fn []
            (jetty/run-jetty
                (fn [req]
                  (handler (assoc app-instance ::ring req)))
                {:port 5002
                 :join? false}))
          :stop
          (fn stop [http]
            (.stop http)
            (rt/stop-all app-instance))
          }}]

    (-> {::started (System/currentTimeMillis)
         :app app-instance}
        (rt/init ext-def)
        (rt/start-all))))
