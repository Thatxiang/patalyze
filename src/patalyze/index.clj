(ns patalyze.index
  (:require [patalyze.retrieval   :as retrieval]
            [patalyze.parser      :as parser]
            [environ.core         :refer [env]]
            [patalyze.config      :refer [c es]]
            [riemann.client       :as r]
            [schema.core          :as s]
            [taoensso.timbre      :as timbre :refer (log  trace  debug  info  warn  error)]
            [clojurewerkz.elastisch.rest          :as esr]
            [clojurewerkz.elastisch.rest.index    :as esi]
            [clojurewerkz.elastisch.query         :as q]
            [clojurewerkz.elastisch.aggregation   :as a]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.bulk     :as esb]
            [clojurewerkz.elastisch.rest.response :as esresp])
  (:import (java.util.concurrent TimeUnit Executors)))

(def ^:dynamic *bulk-size* 3000)

; (def patent-count-notifier
;   (.scheduleAtFixedRate (Executors/newScheduledThreadPool 1)
;     #(r/send-event @c {:ttl 20 :service "patalyze.index/document-count"
;                       :state "ok" :metric (:count (esd/count @es "patalyze_development" "patent" (q/match-all)))})
;     0 10 TimeUnit/SECONDS))

;; BULK INSERTION
(def ^:private special-operation-keys
  [:_index :_type :_id :_routing :_percolate :_parent :_timestamp :_ttl])

(defn upsert-operation [doc]
  {"update" (select-keys doc special-operation-keys)})

(defn upsert-document [doc]
  {:doc (dissoc doc :_index :_type)
   :doc_as_upsert true})

(defn bulk-upsert
  "generates the content for a bulk insert operation"
  ([documents]
     (let [operations (map upsert-operation documents)
           documents  (map upsert-document  documents)]
       (interleave operations documents))))

(defn prepare-bulk-op [patents]
  (bulk-upsert
    (map #(assoc % :_index "patalyze_development"
                   :_type "patent"
                   :_id (:uid %)) patents)))

(defn partitioned-bulk-op [patents]
  (doseq [pat (partition-all *bulk-size* patents)]
    (let [res (esb/bulk @es (prepare-bulk-op pat))]
      (info (count pat) "patents upserted in " (:took res)))))
      ; (r/send-event @c {:ttl 20 :service "patalyze.bulk"
      ;                   :description (str (count pat) " patents upserted")
      ;                   :metric (:took res) :state (if (:errors res) "error" "ok")}))))

(def PatentApplication
  {:uid s/Str
   :title s/Str
   :abstract s/Str
   :inventors  [(s/one s/Str "inventor")
                s/Str]
   :assignees [{:orgname s/Str
               :role s/Str }]})

; ELASTISCH
(def cmapping
  { "patent"
    { :properties
      { :inventors  { :type "string" :index "not_analyzed" }
        :xml-source { :type "string" :index "not_analyzed" }
        :publication-date { :type "date" }
        :filing-date      { :type "date" }}}})

;; using analyzer :analyzer "whitespace" we can search for parts of the inventors name
;; with :index "not_analyzed"

;; INDEX WITH ELASTISCH
(defn index-files [files]
  (partitioned-bulk-op
    (flatten (map parser/read-file files))))

; (defn complete-reindex! []
;   (let [nd (retrieval/not-downloaded)]
;     (pmap index-files (partition-all (/ (count nd) )))))

; (.. Runtime getRuntime availableProcessors)

(defn create-elasticsearch-mapping []
  (esi/create @es "patalyze_development" :mappings cmapping))

(defn patent-count []
  (esd/count @es "patalyze_development" "patent" (q/match-all)))

(defn clear-patents []
  (esd/delete-by-query-across-all-indexes-and-types @es (q/match-all)))

(defn count-for-range [from to]
  (esresp/total-hits (esd/search @es "patalyze_development" "patent"
                                 :query (q/range :publication-date :from from :to to))))

(defn count-patents-in-archives []
  (reduce + (map #(count (retrieval/read-and-split-from-zipped-xml %))
                 (retrieval/patent-application-files))))

(defn merge-mapfile! [file map-to-merge]
  (if (.exists (clojure.java.io/as-file file))
    (spit file
      (merge
        (read-string (slurp file))
        map-to-merge))
    (spit file map-to-merge)))

(defn update-archive-stats-file! [archives]
  (let [stats-file   (str (env :data-dir) "/archive-stats.edn")]
    (merge-mapfile! stats-file
      (into {}
        (for [f archives]
           {(apply str (re-seq #"\d{8}" f)) (count (retrieval/read-and-split-from-zipped-xml f))})))))

(defn archive-stats []
  (let [stats-file (str (env :data-dir) "/archive-stats.edn")
        on-disk    (retrieval/patent-application-files)]
    (if (not (.exists (clojure.java.io/as-file stats-file)))
      (do
        (update-archive-stats-file! on-disk)
        (archive-stats))
      (do
        (update-archive-stats-file!
          (remove #(some #{(apply str (re-seq #"\d{8}" %))}
                         (keys (read-string (slurp stats-file))))
                  on-disk))
        (read-string (slurp stats-file))))))

(defn database-stats []
  (let [agg (esd/search @es "patalyze_development" "patent"
                        { :query (q/match-all)
                          :aggregations {:dates (a/terms "publication-date" {:size 0})}})
        pub-dates (get-in agg [:aggregations :dates :buckets])]
    (merge
      (into {}
        (for [f (retrieval/patent-application-files)]
           {(apply str (re-seq #"\d{8}" f)) 0}))
      (into {}
        (for [p pub-dates]
          {(apply str (re-seq #"\d{8}" (:key_as_string p))) (:doc_count p)})))))

(defn index-integrity-stats []
  (merge-with #(zipmap [:archive :database] %&) (archive-stats) (database-stats)))

(defn archive-for-date [date-string]
  (first (filter #(= (apply str (re-seq #"\d{8}" %)) date-string)
                  (retrieval/patent-application-files))))

(defn incompletely-indexed-archives []
  (let [stats      (index-integrity-stats)
        incomplete (select-keys stats (for [[k v] stats :when (not= (:database v) (:archive v))] k))]
  (into {}
    (for [[date s] incomplete]
      {(archive-for-date date) s}))))

(defn scroll [search-res]
  (map :_source
    (esd/scroll-seq @es search-res)))

(defn patents-by-inventor [inventor]
  (esd/search @es "patalyze_development" "patent"
              :query (q/match :inventors inventor :operator :and)
              :sort :publication-date
              :scroll "1m"
              :size 20))

(defn patents-by-org [org]
  (scroll
   (esd/search @es "patalyze_development" "patent"
               :query (q/match :organization org :operator :and)
               :sort :publication-date
               :scroll "1m"
               :size 20)))

(defn inventors-of-organization [org]
    (let [aggregation (esd/search @es "patalyze_development" "patent"
                                { :query (q/match :organization org :operator :and)
                                  :aggregations {:inventors (a/terms "inventors" {:size 0})}})
          inventors (->> aggregation :aggregations :inventors :buckets (map :key))]
      (set inventors)))

(defn peers-of-inventors-of-organization [org]
  (let [inventors (inventors-of-organization org)]
    (clojure.set/difference
     (set (flatten (map :inventors
                        (scroll
                         (esd/search @es "patalyze_development" "patent"
                                     :query { :terms { :inventors inventors }}
                                     :sort :publication-date
                                     :scroll "1m"
                                     :size 20)))))
     inventors)))

(defn hidden-patents [org]
  (let [inventors (inventors-of-organization org)
        external  (peers-of-inventors-of-organization org)]
    (scroll
     (esd/search @es "patalyze_development" "patent"
                 :query (q/bool { :must { :terms { :inventors inventors }}
                                 :must_not [{ :terms { :inventors external }}
                                            (q/match :organization org)]})
                 :sort :publication-date
                 :scroll "1m"
                 :size 20))))

(defn co-patents [org]
  (let [inventors (inventors-of-organization org)]
    (scroll
     (esd/search @es "patalyze_development" "patent"
                 :query (q/bool { :must { :terms { :inventors inventors }}
                                  :must_not (q/match :organization org)})
                 :sort :publication-date
                 :scroll "1m"
                 :size 20))))

;; (database-stats)
;; (patent-count)
;; (count (archive-stats))
;; (incompletely-indexed-archives)
;; (count (read-file (first (keys (incompletely-indexed-archives)))))
;; (retrieval/patent-application-files)
;; (count (patents-by-org "Apple Inc"))

(defn tier-stats-for-org [org]
  (let [patents (patents-by-org org)
        hidden  (hidden-patents org)
        copats  (co-patents org)]
    {:patents (count patents)
     :hidden  (count hidden)
     :copats  (count copats)
     :dupes   (count (clojure.set/intersection (set patents) (set hidden) (set copats)))}))

;; (patent-count)
;; (count (patents-by-org "Apple Inc"))
;; (co-patents "Apple Inc")
;; (tier-stats-for-org "International Business Machines Corporation")
;; (tier-stats-for-org "Apple Inc")
;; (count (patents-by-org "International Business Machines Corporation"))

; get list of people that explicitly published patents for apple
; get list of people that worked with these people but did not publish patents under apple
;
;  (esd/search @es "patalyze_development" "patent"
;              :query (q/match :organization org :operator :and)
;              :sort :publication-date
;              :scroll "1m"
;              :size 20))

; (deref es)
; (update-archive-stats-file!)
; (patent-count)
; (retrieval/not-downloaded)
; (archive-stats)
; (incompletely-indexed-archives)
; (map :_source
;      (esd/scroll-seq @es
;                      (esd/search @es "patalyze_development" "patent" :query (q/match-all) :filter {:exists { :field :organization}} :sort :publication-date)))
; (patents-by-org "Apple")
; (patents-by-inventor "Duncan Kerr")
; (def some-patents
;   (read-file (first (retrieval/patent-application-files))))

(comment
  (patent-count) ;; 3777307
  (pmap index-files (partition-all 
    (reverse (retrieval/patent-application-files))))
  (map (comp :organization :_source)
    (esd/scroll-seq @es
      (esd/search @es "patalyze_development" "patent"
        :query (q/match :organization "Apple Inc."))))

  (count (map :organization
    (patents-by-org-and-date "Apple Inc." "20140612")))
  (count (map :organization
    (patents-by-org-and-date "Apple Inc." "20140619")))

  (set (map :organization
    (patents-by-inventor "Christopher D. Prest")))
  (count
    (patents-by-org-and-date "Apple Inc." "20140612"))
  (esi/update-mapping @es "patalyze_development" "patent" :mapping cmapping) 

  (connect-elasticsearch)
  (:count (esd/count "patalyze_development" "patent" (q/match-all)))
  (retrieval/patent-application-files)
  (apply queue-archive (retrieval/patent-application-files))
  (patent-count)
  (esd/delete-by-query-across-all-indexes-and-types (q/match-all))
  (esd/search "patalyze_development" "patent" :query (q/match-all))
  (esd/delete-by-query-across-all-indexes-and-types (q/match-all))
  ;; creates an index with default settings and no custom mapping types
  (time (index-file (nth (patent-application-files) 4)))
  (esd/count "patalyze_development" "patent" (q/match-all))
  (esd/count "patalyze_development" "patent" (q/match-all))
  (esi/update-mapping "patalyze_development" "patent" :mapping cmapping)
  (esi/refresh "patalyze_development")
  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Christopher D. Prest"))
  (esd/search "patalyze_development" "patent" :query {:term {:inventors "Daniel Francis Lowery"}})
  (esd/search "patalyze_development" "patent" :query (q/term :title "plastic"))
  (esd/search "patalyze_development" "patent" :query (q/term :inventors "Prest"))
  (esresp/total-hits (esd/search "patalyze_development" "patent" :query {:term {:title "plastic"}})))
