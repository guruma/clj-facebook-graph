(ns vita.facebook
  (:require [ring.util.codec :as codec]
            [ring.util.response :as res :refer [redirect response]]
            [ring.middleware.keyword-params :as param :refer [wrap-keyword-params]]
            [uri.core :as uri]
            [clojure.data.json :as json]
            [clj-oauth2.client :as oauth2]
            [pandect.core :as pan]
            [hiccup.core :as hiccup :refer [html]]
            [clj-http.client :as http]
            [clj-time [core :as time]
                      [format :as timef]
                      [local :as local]]))

(defn not-nil? [a]
  (not (nil? a)))

(defn debug [& args]
  (println (apply str args)))

;;----------------------------------------------------
;; config info for facebook.
;;----------------------------------------------------

(def facebook-login-dialog-path "https://www.facebook.com/dialog/oauth?")

(defn- fb-uri [path]
  (str "https://graph.facebook.com" path))

(def facebook-oauth2-endpoint
  {:access-query-param :access_token
   :authorization-uri (fb-uri "/oauth/authorize")
   :access-token-uri (fb-uri "/oauth/access_token")})

(def app-info {
  :app-id "your app id"
  :app-secret-code "your app sectrt code"
  :redirect-uri "https://localhost/vita-login/"
  :canvas-page-uri "https://apps.facebook.com/latte-dev"
  :game-uri "https://localhost/"
  :user-app-login-path "your app login path"
  :grant-type "authorization_code"
  :scope  ["read_friendlists","read_stream","publish_actions","user_photos"]
})

(let [{:keys [app-id app-secret-code redirect-uri grant-type] :as app} app-info]
  (def facebook-endpoint
    (merge facebook-oauth2-endpoint
           (assoc app :client-id app-id :client-secret app-secret-code :redirect-uri redirect-uri))))

;;----------------------------------------------------
;; signed request
;;----------------------------------------------------

(defn- bytes->string [bytes]
  (apply str (map char bytes)))

(defn- base64url-decode [xs]
  (-> xs
      (.replace "/" "_")
      (.replace "-" "+")
      codec/base64-decode))

(defn- parse-signed-request
  "parse the signed request from facebook app link."
  [signed-request]
  (println "------------- parse-signed-request ---------------")
;;   (println signed-request)
  (let [[encoded-sig payload] (.split signed-request "\\.")
        sig (base64url-decode encoded-sig)
        data (-> payload base64url-decode bytes->string json/read-json)
        expected-sig (pan/sha256-hmac-bytes payload (:app-secret-code app-info))]
;;     (if (java.util.Arrays/equals sig expected-sig)
;;     (println "sig = " (String. sig) ", exprected-sig = " (String. expected-sig))
    (debug data)
    (if (= (String. sig) (String. expected-sig))
      data
      (println "vita error in parse signed-request."))))


;;----------------------------------------------------
;; facebook request check predicates
;;----------------------------------------------------

(defn- logined-user?
  "check for user to be facebook loggined."
  [data]
  (let [r (and (:oauth_token data)
               (:user_id data))]
    (debug "------------- facebook logined-user? " (not-nil? r) "---------------")
    r))

(defn- facebook-signed-request?
  "a predicate to check for facebook singed request from game link."
  [req]
  (let [r (and (= :post (:request-method req))
               (not-nil? (get-in req [:params "signed_request"])))]
    (debug "------------- facebook-signed-request? " r "-------------")
    r))

(defn- request-for-app-login? [req]
  (let [r (and (= :get (:request-method req))
               (= (:uri req) (:user-app-login-path app-info) ))]
;;                (= (:uri req) (.getPath (java.net.URI. (:redirect-uri app-info)))))
    (debug "------------- request-for-app-login? " r "---------------")
    r))

(defn- request-redirected-from-facebook-login-dialog?
  "페이스북 Login Dialog로부터 redirect된 request인지 검사"
  [req]
  (let [r (and (request-for-app-login? req)
               (not-nil? (get-in req [:params "code"])))]
    (debug "------------- request-from-facebook-login-dialog? " r "---------------")
    r ))

;;----------------------------------------------------
;; redirections
;;----------------------------------------------------

(defn- make-state-string-for-facebook-login []
  (str (java.util.UUID/randomUUID)))

(defn- redirect-in-iframe [uri]
  (debug "------------- redirect-in-iframe ---------------")
  (let [url (html [:html [:body [:script {:type "text/javascript"}
                                 (str "window.parent.top.location.href=\"" uri "\";")]]])]
  (debug url)
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body url}))

(defn- redirect-user-to-facebook-login-dialog [request]
  (debug "------------- redirect-user-to-facebook-login-dialog -------------")
  (let [state (make-state-string-for-facebook-login)
        login-uri (:uri (oauth2/make-auth-request facebook-endpoint state))]
    (debug login-uri)
    (redirect-in-iframe login-uri )))

(defn- exchange-code-for-access-token [req params]
  (:access-token (oauth2/get-access-token
                  facebook-endpoint
                  params
                  {:state (:state params) :scope nil})))

(defn- get-facebook-user-info [token]
  (let [params {"fields" "id,name,email,username,picture"}
        params (assoc params "access_token" token)
        req {:query-params params}]
    (json/read-json (:body (http/get (str (fb-uri "/me")) req)))))

(defn- redirect-user-to-canvas-page []
  (debug "------------- redirect-user-to-canvas-page ---------------")
  (redirect-in-iframe (:canvas-page-uri app-info)))

(defn- redirect-user-to-game [access-token]
  (debug "------------- redirect-user-to-game -------------")
    (let [fb-user-info (get-facebook-user-info access-token)]
      (debug fb-user-info)
      (redirect (:game-uri app-info))))

(defn- exchange-code-for-access-token [params]
  (:access-token (oauth2/get-access-token
                  facebook-endpoint
                  params
                  {:state (:state params) :scope nil})))

(defn- response-error [params]
  (let [error-code (params "error_code")]
    (response "access denied")))

;;----------------------------------------------------
;; ring middleware
;;----------------------------------------------------

(defn facebook-request
  "a ring middleware to process facebook requests for game login and redirect url."
  [req]
  (debug "------------- REQUEST ---------------")
  (debug (:request-method req))
  (debug (:uri req))
  (debug (:params req))
  (cond
   (request-redirected-from-facebook-login-dialog? req)
   (redirect-user-to-canvas-page)

   (request-for-app-login? req)
   (if (get-in req [:params "error"])
     (response-error (:params req))
     (redirect-user-to-facebook-login-dialog req))

   (facebook-signed-request? req)
   (let [login-data (parse-signed-request (get-in req [:params "signed_request"]))]
     (if (logined-user? login-data)
       (redirect-user-to-game (:oauth_token login-data))
       (redirect-user-to-facebook-login-dialog req)))))

(defn wrap-facebook
  "a wrapper function for a facebook middleware."
  [handler]
  (fn [req]
    (debug "\n=============  WRAP FACEBOOK HANDLER ==================")
    (or (facebook-request req)
        (handler req))))
