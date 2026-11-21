(ns bench.runner
  (:require
    [bench.client :as client]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str])
  (:import
    [java.io BufferedReader InputStreamReader]
    [java.lang ProcessBuilder$Redirect]
    [java.net HttpURLConnection URI]
    [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def default-servers
  ["shadow-http"
   "shadow-http-native"
   "jetty"
   "http-kit"])

(def test-descriptions
  {"http-plaintext" "HTTP GET /plaintext (keep-alive)"
   "http-json"      "HTTP GET /json (keep-alive)"
   "http-post"      "HTTP POST /echo 1 KB (keep-alive)"
   "sse-stream"     "SSE GET /sse (flushed event stream, latency = TTFE)"
   "ws-echo"        "WebSocket /ws echo (64 B text frames)"
   "ws-echo-4k"     "WebSocket /ws echo (4 KB text frames)"})

(def test-units
  {"http-plaintext" "req/s"
   "http-json"      "req/s"
   "http-post"      "req/s"
   "sse-stream"     "events/s"
   "ws-echo"        "msg/s"
   "ws-echo-4k"     "msg/s"})

(defn- java-bin []
  (str (System/getProperty "java.home") "/bin/java"))

(defn- current-classpath []
  (System/getProperty "java.class.path"))

(defn- wait-for-health [host port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        url (.toURL (URI/create (str "http://" host ":" port "/health")))]
    (loop []
      (let [ok? (try
                  (let [^HttpURLConnection conn (.openConnection url)]
                    (.setConnectTimeout conn 500)
                    (.setReadTimeout conn 500)
                    (try
                      (= 200 (.getResponseCode conn))
                      (finally
                        (.disconnect conn))))
                  (catch Exception _ false))]
        (cond
          ok? true
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info (str "Timed out waiting for server health on " host ":" port)
                          {:host host :port port}))
          :else
          (do (Thread/sleep 100)
              (recur)))))))

(defn- start-server-process
  [{:keys [server host port jvm-opts jetty-max-threads]}]
  (let [cmd (vec (concat [(java-bin)]
                         jvm-opts
                         ["-cp" (current-classpath)
                          "clojure.main" "-m" "bench.server"
                          server
                          "--host" host
                          "--port" (str port)
                          "--jetty-max-threads" (str jetty-max-threads)]))
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true))
        proc (.start pb)
        reader (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
    ;; Wait until the server prints READY or exits
    (let [deadline (+ (System/currentTimeMillis) 30000)
          output (StringBuilder.)]
      (loop []
        (cond
          (> (System/currentTimeMillis) deadline)
          (do (.destroyForcibly proc)
              (throw (ex-info (str "Server " server " did not become ready in time:\n" output)
                              {:server server})))

          (.ready reader)
          (if-let [line (.readLine reader)]
            (do (.append output line)
                (.append output "\n")
                (if (str/starts-with? line "READY ")
                  (do
                    ;; Drain remaining server output asynchronously so stdout pipe never blocks
                    (Thread/startVirtualThread
                      (fn []
                        (try
                          (while (.readLine reader))
                          (catch Exception _))))
                    (wait-for-health host port 10000)
                    proc)
                  (recur)))
            (throw (ex-info (str "Server " server " exited early:\n" output)
                            {:server server})))

          (not (.isAlive proc))
          (do (while (.ready reader)
                (.append output (.readLine reader))
                (.append output "\n"))
              (throw (ex-info (str "Server " server " exited with code " (.exitValue proc) ":\n" output)
                              {:server server})))

          :else
          (do (Thread/sleep 50)
              (recur)))))))

(defn- stop-server-process [^Process proc]
  (when (and proc (.isAlive proc))
    (.destroy proc)
    (when-not (.waitFor proc 5 TimeUnit/SECONDS)
      (.destroyForcibly proc)
      (.waitFor proc 3 TimeUnit/SECONDS)))
  ;; Brief pause so the OS releases the listening socket cleanly
  (Thread/sleep 200))

(defn- run-client-process
  [{:keys [host port tests connections warmup duration sse-events client-jvm-opts]}]
  (let [cmd (vec (concat [(java-bin)]
                         client-jvm-opts
                         ["-cp" (current-classpath)
                          "clojure.main" "-m" "bench.client"
                          "--host" host
                          "--port" (str port)
                          "--tests" (str/join "," tests)
                          "--connections" (str connections)
                          "--warmup" (str warmup)
                          "--duration" (str duration)
                          "--sse-events" (str sse-events)
                          "--edn"]))
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectError ProcessBuilder$Redirect/INHERIT))
        proc (.start pb)
        stdout (slurp (.getInputStream proc))
        exit (.waitFor proc)]
    (when-not (zero? exit)
      (throw (ex-info (str "Benchmark client failed with exit code " exit)
                      {:exit exit :stdout stdout})))
    (edn/read-string stdout)))

(defn- format-us [us]
  (cond
    (< us 1000.0) (format "%.1f us" (double us))
    (< us 1000000.0) (format "%.2f ms" (/ (double us) 1000.0))
    :else (format "%.2f s" (/ (double us) 1000000.0))))

(defn- print-summary-tables [servers tests results-by-server]
  (let [baseline (if (some #{"shadow-http"} servers)
                   "shadow-http"
                   (first servers))]
    (println)
    (println "================================================================================")
    (println (format "BENCHMARK SUMMARY (relative throughput vs %s)" baseline))
    (println "================================================================================")
    (doseq [t tests]
      (let [unit (get test-units t "ops/s")
            base-ops (some-> (get-in results-by-server [baseline t :opsPerSec]) double)
            lat-prefix (if (= t "sse-stream") "TTFE " "")]
        (println)
        (println (str "### " t " — " (get test-descriptions t "")))
        (println (format "| %-20s | %21s | %8s | %10s | %10s | %10s | %6s |"
                   "Server"
                   (str "Throughput (" unit ")")
                   "vs base"
                   (str lat-prefix "p50")
                   (str lat-prefix "p95")
                   (str lat-prefix "p99")
                   "Errors"))
        (println (str "|" (apply str (repeat 22 "-"))
                      "|" (apply str (repeat 23 "-"))
                      "|" (apply str (repeat 10 "-"))
                      "|" (apply str (repeat 12 "-"))
                      "|" (apply str (repeat 12 "-"))
                      "|" (apply str (repeat 12 "-"))
                      "|" (apply str (repeat 8 "-")) "|"))
        (doseq [s servers]
          (when-let [r (get-in results-by-server [s t])]
            (let [ops (double (:opsPerSec r))
                  rel (if (and base-ops (pos? base-ops))
                        (format "%.2fx" (/ ops base-ops))
                        "-")]
              (println (format "| %-20s | %,21.0f | %8s | %10s | %10s | %10s | %6d |"
                         s
                         ops
                         rel
                         (format-us (:p50Us r))
                         (format-us (:p95Us r))
                         (format-us (:p99Us r))
                         (long (:errors r)))))))))
    (println)))

(defn- print-usage []
  (println "Usage: ./run.sh [options]")
  (println)
  (println "Options:")
  (println "  --servers LIST          Comma-separated servers (default: shadow-http,shadow-http-native,jetty,http-kit)")
  (println "  --tests LIST            Comma-separated tests (default: http-plaintext,http-json,http-post,sse-stream,ws-echo,ws-echo-4k)")
  (println "  --connections N         Concurrent client connections per test (default: 32)")
  (println "  --warmup SEC            Warmup seconds per test (default: 2)")
  (println "  --duration SEC          Measured seconds per test (default: 5)")
  (println "  --sse-events N          Events streamed per SSE request (default: 500)")
  (println "  --port PORT             Server port (default: 18080)")
  (println "  --jetty-max-threads N   Jetty thread pool max-threads (default: 200)")
  (println "  --jvm-opts OPTS         Space-separated JVM flags for server JVMs (default: \"-Xms512m -Xmx512m\")")
  (println "  --out FILE              Write raw EDN results to FILE")
  (println "  --help                  Show this help"))

(defn- parse-args [args]
  (loop [[a & more] args
         opts {:servers default-servers
               :tests client/default-tests
               :host "127.0.0.1"
               :port 18080
               :connections 32
               :warmup 2
               :duration 5
               :sse-events 500
               :jetty-max-threads 200
               :jvm-opts ["-Xms512m" "-Xmx512m"]
               :client-jvm-opts ["-Xms512m" "-Xmx512m"]
               :out nil}]
    (cond
      (nil? a) opts
      (or (= a "--help") (= a "-h")) (do (print-usage) (System/exit 0))
      (= a "--servers")           (recur (rest more) (assoc opts :servers (str/split (first more) #",")))
      (= a "--tests")             (recur (rest more) (assoc opts :tests (str/split (first more) #",")))
      (= a "--host")              (recur (rest more) (assoc opts :host (first more)))
      (= a "--port")              (recur (rest more) (assoc opts :port (parse-long (first more))))
      (= a "--connections")       (recur (rest more) (assoc opts :connections (parse-long (first more))))
      (= a "--warmup")            (recur (rest more) (assoc opts :warmup (parse-long (first more))))
      (= a "--duration")          (recur (rest more) (assoc opts :duration (parse-long (first more))))
      (= a "--sse-events")        (recur (rest more) (assoc opts :sse-events (parse-long (first more))))
      (= a "--jetty-max-threads") (recur (rest more) (assoc opts :jetty-max-threads (parse-long (first more))))
      (= a "--jvm-opts")          (recur (rest more) (assoc opts :jvm-opts (remove str/blank? (str/split (first more) #"\s+"))))
      (= a "--out")               (recur (rest more) (assoc opts :out (first more)))
      :else (throw (ex-info (str "Unknown option: " a) {:arg a})))))

(defn -main [& args]
  (let [{:keys [servers tests connections warmup duration sse-events out] :as opts} (parse-args args)]
    (println (format "Benchmark config: servers=%s | tests=%s | connections=%d | warmup=%ds | duration=%ds | sse-events=%d"
               (str/join "," servers)
               (str/join "," tests)
               connections warmup duration sse-events))
    (println (format "Java: %s (%s) | CPUs: %d"
               (System/getProperty "java.version")
               (System/getProperty "java.vm.name")
               (.availableProcessors (Runtime/getRuntime))))
    (flush)

    (let [results-by-server
          (reduce
            (fn [acc server]
              (println)
              (println (str ">>> Benchmarking " server " ..."))
              (flush)
              (let [proc (start-server-process (assoc opts :server server))]
                (try
                  (let [res (run-client-process opts)
                        by-test (into {} (map (juxt :test identity)) res)]
                    (assoc acc server by-test))
                  (finally
                    (stop-server-process proc)))))
            {}
            servers)]

      (print-summary-tables servers tests results-by-server)

      (when out
        (let [payload {:meta {:java (System/getProperty "java.version")
                              :cpus (.availableProcessors (Runtime/getRuntime))
                              :timestamp (str (java.time.Instant/now))
                              :config (select-keys opts [:servers :tests :connections :warmup :duration :sse-events :jetty-max-threads])}
                       :results results-by-server}]
          (with-open [w (io/writer out)]
            (pprint/pprint payload w))
          (println (str "Saved raw EDN results to " out)))))))
