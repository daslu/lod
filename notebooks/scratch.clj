(ns scratch
  (:require [aerial.hanami.templates :as ht]
            [camel-snake-kebab.core :as csk]
            [scicloj.kindly.v4.kind :as kind]
            [scicloj.tableplot.v1.plotly :as plotly]
            [scicloj.tableplot.v1.transpile :as transpile]
            [scicloj.tableplot.v1.hanami :as hanami]
            [tablecloth.api :as tc]
            [tablecloth.column.api :as tcc]
            [tech.v3.dataset :as ds]
            [scicloj.tablemath.v1.api :as tm]
            [clojure.math :as math]
            [fastmath.stats :as stats]
            [wkok.openai-clojure.api :as api]
            [charred.api :as charred]
            [clojure.string :as str]
            [geo
             [geohash :as geohash]
             [jts :as jts]
             [spatial :as spatial]
             [io :as geoio]
             [crs :as crs]]
            [std.lang :as l]
            [tech.v3.datatype :as dtype]
            [tech.v3.datatype.functional :as fun]
            [tech.v3.tensor :as tensor]
            [tech.v3.datatype.argops :as argops]
            [tech.v3.dataset.print :as print]
            [tech.v3.libs.fastexcel :as xlsx]))

;; (:import java.time.LocalDateTime
;;          (org.locationtech.jts.geom Geometry Point Polygon Coordinate)
;;          (org.locationtech.jts.geom.prep PreparedGeometry
;;                                          PreparedLineString
;;                                          PreparedPolygon
;;                                          PreparedGeometryFactory)
;;          (org.locationtech.jts.algorithm Centroid))





(defn js
  [& forms]
  ((l/ptr :js)
   (cons 'do forms)))

(defn- js-assignment [symbol data]
  (format "let %s = %s;"
          symbol
          (charred/write-json-str data)))

(defn- js-entry-assignment [symbol0 symbol1 symbol2]
  (format "let %s = %s['%s'];"
          symbol0
          symbol1
          symbol2))

(defn- js-closure [js-statements]
  (->> js-statements
       (str/join "\n")
       (format "(function () {\n%s\n})();")))


(-> "data/גנים בלוד הצלבה אמצע עבודה.xlsx"
    tc/dataset
    (tc/select-columns ["X" "Y" "מגזר"])
    (tc/rename-columns {"X" :X
                        "Y" :Y
                        "מגזר" :migzar})
    (tc/head 89))

(def ganim
  (-> "data/גנים בלוד הצלבה אמצע עבודה.xlsx"
      tc/dataset
      (tc/select-columns ["X" "Y" "מגזר"])
      (tc/rename-columns {"X" :X
                          "Y" :Y
                          "מגזר" :migzar})
      (tc/head 89)))


(def raw-geojson
  (-> "data/%D7%A9%D7%9B%D7%91%D7%AA_%D7%92%D7%A0%D7%99_%D7%99%D7%9C%D7%93%D7%99%D7%9D.geojson"
      slurp
      (charred/read-json {:key-fn keyword})
      :features
      (->> (filter #(-> % :properties :SETL_NAME (= "לוד"))))
      
      vec))



(def prepared-geojson
  (-> ganim
      (tc/map-columns :geometry
                      [:X :Y]
                      (fn [x y]
                        (prn [x y])
                        (->> raw-geojson
                             (filter (fn [{:keys [properties]}]
                                       (some->> properties
                                                ((juxt :X :Y))
                                                (= [x y]))))
                             (map :geometry)
                             first)))
      (tc/rows :as-maps)
      (->> (mapv (fn [{:keys [geometry migzar]}]
                   {:type "Feature"
                    :properties {:migzar migzar}
                    :geometry geometry})))))



(def center
  (-> raw-geojson
      (->> (map #(-> % :geometry :coordinates)))
      tensor/->tensor
      (tensor/reduce-axis fun/mean 0)
      vec))


(map (partial take 2)
     [(->> raw-geojson
           (map (fn [f]
                  (-> f
                      (update :properties #(select-keys % [:X :Y]))))))
      prepared-geojson])


(delay
  (let [data {'center (reverse center)
              'zoom 14
              'provider "Stadia.AlidadeSmooth"
              'geojson prepared-geojson}]
    (kind/hiccup
     [:div {:style {:height "600px"}}
      [:script
       (js-closure
        (concat
         [(js-assignment 'data data)]
         (->> data
              (mapv (fn [[k v]]
                      (js-entry-assignment k 'data k))))
         [(js '(var m (L.map document.currentScript.parentElement))
              '(m.setView center zoom)
              '(-> (L.tileLayer.provider provider)
                   (. (addTo m)))
              '(-> geojson
                   (L.geoJSON {:pointToLayer (fn [feature latlng]
                                               (return
                                                (L.circleMarker
                                                 latlng
                                                 {:radius 8
                                                  ;; :color (get {"ערבי" "orange"
                                                  ;;              "יהודי" "blue"}
                                                  ;;             feature.migzar)
                                                  })))})
                   (. (addTo m))))]))]]
     {:html/deps [:leaflet]})))
