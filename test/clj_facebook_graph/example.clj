; Copyright (c) Maximilian Weber. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v10.html at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns clj-facebook-graph.example
  (:use [ring.util.response :only [redirect]]
        [ring.middleware.stacktrace :only [wrap-stacktrace-web]]
        [ring.middleware.params :only [wrap-params]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.session.memory :only [memory-store]]
        [ring.handler.dump :only [handle-dump]]
;;         [ring.mock.request]
        [clj-facebook-graph.auth :only [with-facebook-auth with-facebook-access-token make-auth-request *facebook-auth*]]
        [clj-facebook-graph.helper :only [facebook-base-url]]
        [clj-facebook-graph.ring-middleware :only [wrap-facebook-access-token-required
                                                   wrap-facebook-extract-callback-code
                                                   wrap-facebook-auth]]
        compojure.core
        hiccup.core
        ring.adapter.jetty)
  (:require [compojure.route :as route]
            [clj-facebook-graph.client :as client])
  (:import [java.lang Exception]
           [clj_facebook_graph FacebookGraphException]))


(def name-id-map (atom {}))

(defn create-friend-id-by-name [friends]
  (into {} (map (fn [{:keys [name id]}]
                  [name id]) friends)))

(defn get-friends-name-id-mapping [facebook-auth]
  (let [access-token (:access_token facebook-auth)]
    (if-let [friends-name-id-mapping (@name-id-map access-token)]
      friends-name-id-mapping
      (let [friends-id-by-name (create-friend-id-by-name
                                (client/get [:me :friends] {:extract :data}))]
        (do (swap! name-id-map assoc access-token friends-id-by-name)
            friends-id-by-name)))))

(defn wrap-facebook-id-by-name [client]
  (fn [request]
    (let [url (:url request)]
      (if (and *facebook-auth* (vector? url))
        (let [[name] url]
          (if-let [name (:name name)]
            (let [friends-name-id-mapping
                  (get-friends-name-id-mapping *facebook-auth*)
                  id (friends-name-id-mapping name)
                  request (assoc request :url (assoc url 0 id))]
              (client request))
            (client request)))
        (client request)))))

(def request (wrap-facebook-id-by-name #'clj-facebook-graph.client/request))

(defn fb-get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :get :url url})))

(defn get-album-overview [id]
  (map (fn [{:keys [id name]}]
         {:name name
          :preview-image (with-facebook-access-token
                           (str facebook-base-url "/" id "/picture"))})
       (fb-get [id :albums] {:extract :data})))

(defn render-album-overview [id]
  (html (map (fn [{:keys [name preview-image]}]
               [:div [:h3 name] [:img {:src preview-image}]])
             (get-album-overview id))))

(defonce facebook-app-info {:client-id "your Facebook app id"
                            :client-secret "your Facebook app's secret"
                            :redirect-uri "http://localhost:8080/facebook-callback"
                            :scope  ["user_photos" "friends_photos" "publish_stream"]})

(defroutes app
  (GET "/albums/:id" [id]
       (if (not clj-facebook-graph.auth/*facebook-auth*)
         (throw
          (FacebookGraphException.
           {:error :facebook-login-required}))
         (if (.contains id "_")
           (render-album-overview {:name (.replaceAll id "_" " ")})
           (render-album-overview id))))
  (GET "/show-session" {session :session} (str session))
  (route/not-found "Page not found"))

(def session-store (atom {}))

(defn wrap-app [app facebook-app-info]
  (-> app
      (wrap-facebook-auth facebook-app-info "/facebook-login")
      (wrap-facebook-extract-callback-code facebook-app-info handle-dump)
      (wrap-facebook-access-token-required facebook-app-info)
      (wrap-session {:store (memory-store session-store)})
      (wrap-params)
      (wrap-stacktrace-web)))

(def the-app (wrap-app app facebook-app-info))

(def req1
{:ssl-client-cert nil, :remote-addr "221.150.124.66", :scheme :http,
 :query-params {"fb_bmpos" "1_0", "count" "0", "ref" "bookmarks", "fb_source" "bookmark"},
 :form-params {"fb_locale" "ko_KR", "signed_request" "aaaa"},
 :multipart-params {}, :request-method :get,
 :query-string "fb_source=bookmark&ref=bookmarks&count=0&fb_bmpos=1_0",
 :content-type "application/x-www-form-urlencoded",
 :uri "/facebook-callback", :server-name "localhost",
 :params {"code" "code",  "fb_source" "bookmark", "ref" "bookmarks", "count" "0", "fb_bmpos" "1_0", "signed_request" "aaa", "fb_locale" "ko_KR"},
 :headers {"user-agent" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.9; rv:27.0) Gecko/20100101 Firefox/27.0", "cookie" "JSESSIONID=A6F26C5086B08EA50FBBBBC99F3463E3; ring-session=8a3cdd3d-48b6-421d-b5de-6bf35f9063ec", "accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "host" "weebinar.com", "referer" "https://apps.facebook.com/latte-dev/?fb_source=bookmark&ref=bookmarks&count=0&fb_bmpos=1_0", "content-type" "application/x-www-form-urlencoded", "accept-encoding" "gzip, deflate", "content-length" "589", "accept-language" "ko-kr,ko;q=0.8,en-us;q=0.5,en;q=0.3", "connection" "keep-alive"},
 :content-length 589, :server-port 8080, :character-encoding nil,
 :body ""})

(the-app req1)

(defn start-server []
  (future (run-jetty (var the-app) {:port 8080 :join? false})))


;(def server (start-server))
;(.stop (.get server))


(def example-wall-post
     {:message "Check out this funny article"
      :link "http://www.example.com/article.html"
      :picture "http://www.example.com/article-thumbnail.jpg'"
      :name "Article Title"
      :caption "Caption for the link"
      :description "Longer description of the link"
      :actions "{\"name\": \"View on Zombo\", \"link\": \"http://www.zombo.com\"}"
      :privacy "{\"value\": \"ALL_FRIENDS\"}"
      :targeting "{\"countries\":\"US\",\"regions\":\"6,53\",\"locales\":\"6\"}"})

(defmacro with-facebook-auth-by-name [name & body]
  (list* 'clj-facebook-graph.auth/with-facebook-auth-by-name
         'clj-facebook-graph.client/get
         @session-store name body))
