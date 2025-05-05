^{:clay {:hide-code true}}
(ns lod1
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
            [tech.v3.libs.fastexcel :as xlsx]
            [hiccup.core :as hiccup]))

(defn js
  [& forms]
  ((l/ptr :js)
   (cons 'do forms)))

(defn js-assignment [symbol data]
  (format "let %s = %s;"
          symbol
          (charred/write-json-str data)))

(defn js-entry-assignment [symbol0 symbol1 symbol2]
  (format "let %s = %s['%s'];"
          symbol0
          symbol1
          symbol2))

(defn js-closure [js-statements]
  (->> js-statements
       (str/join "\n")
       (format "(function () {\n%s\n})();")))


#_(-> "data/גנים בלוד הצלבה אמצע עבודה.xlsx"
      tc/dataset
      (tc/select-columns ["X" "Y" "מגזר"])
      (tc/rename-columns {"X" :X
                          "Y" :Y
                          "מגזר" :migzar})
      (tc/head 89))

(def ganim
  (-> "data/גנים בלוד הצלבה אמצע עבודה.xlsx"
      tc/dataset
      
      (tc/rename-columns {"X" :X
                          "Y" :Y})
      (tc/select-columns [:X :Y
                          "מגזר"
                          "כתובת"
                          "שם מוסד"
                          "שלב חינוך"
                          "סוג חינוך"
                          "סוג פיקוח"])
      (tc/head 89)
      (tc/drop-rows #(= (%
                         "שם מוסד")
                        "תאליה"))))


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
      (->> (mapv (fn [{:as row
                       :keys [geometry]}]
                   {:type "Feature"
                    :properties (dissoc row :geometry)
                    :geometry geometry})))))



(def center
  (-> raw-geojson
      (->> (map #(-> % :geometry :coordinates)))
      tensor/->tensor
      (tensor/reduce-axis fun/mean 0)
      vec))


(delay
  (let [data {'center (reverse center)
              'zoom 14
              'provider "OpenStreetMap.Mapnik"
              'geojson (->> prepared-geojson
                            (map
                             (fn [feature]
                               (-> feature
                                   (update
                                    :properties
                                    (fn [properties]
                                      (assoc properties
                                             :color (-> properties
                                                        (get "מגזר")
                                                        ({"ערבי"
                                                          "#B85C1E"
                                                          "יהודי"
                                                          "#2A4D8F"}))
                                             :popup (-> properties
                                                        (select-keys ["שם מוסד"
                                                                      "כתובת"
                                                                      "מגזר"
                                                                      "שלב חינוך"
                                                                      "סוג חינוך"
                                                                      "סוג פיקוח"])
                                                        (->> (map (fn [[k v]]
                                                                    [:tr
                                                                     [:td [:b k]]
                                                                     [:td v]]))
                                                             (into [:table])
                                                             hiccup/html)))))))))}]
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
                                                  :color feature.properties.color})))
                               :onEachFeature (fn [feature layer]
                                                (layer.bindPopup feature.properties.popup))})
                   (. (addTo m))))]))]]
     {:html/deps [:leaflet]})))
