(ns puppetlabs.rbac-client.core
  "This is the standard api-caller interface for talking to json apis with pe-style errors"
  (:require
   [clojure.walk :refer [keywordize-keys]]
   [slingshot.slingshot :refer [throw+]]
   ;[puppetlabs.http.client.sync :refer [create-client]]
   [puppetlabs.http.client.common :refer [make-request]]
   ;[puppetlabs.certificate-authority.core :as ssl]
   [puppetlabs.kitchensink.json :as json])
  (:import [com.fasterxml.jackson.core JsonParseException]))

(defn- http-error?
  "Given a response map from the RBAC HTTP API, checks to see if it is a 4xx or
  5xx."
  [response]
  (>= (:status response) 400))

(defn http-error-selector
  "Use this with slingshot to catch all errors orginating in the client"
  [error]
  (and (map? error)
       (keyword? (:kind error))
       (= "puppetlabs.rbac-client" (-> error :kind namespace))))

(defn api-caller
  "Given a client, base-url, method, path and optionally opts makes a request
  to base-url+path using the client standardizing errors into a standard format.

  Throws slingshot maps of the format {:kind :msg :exception :details}"

  ([client base-url method path] (api-caller client base-url method path {}))
  ([client base-url method path opts]
   (let [throw-rest-errors (:status-errors opts)
         opts (-> opts
                  (dissoc :status-errors)
                  (#(merge {:as :text} %)))
         pe-errors? (dissoc opts)
         url (str base-url path)
         response (try
                    (make-request client url method opts)
                    (catch java.net.ConnectException e
                      (throw+ {:kind :puppetlabs.rbac-client/connection-failure
                               :details {:exception (.toString e)}
                               :msg (str "Could not connect to server with " url)})))]
     (if (and throw-rest-errors (http-error? response))
       (throw+ {:kind :puppetlabs.rbac-client/status-error
                :details {:status (:status response)
                          :body (:body response)}
                :msg (format "Error %sing to %s Status: %d" (name method) url (:status response))
                :response (dissoc response :opts)}))
       response)))

(defn json-api-caller
  "Wraps api caller but will convert the body of the request/response to/from json.
  Adds approrpriate content type headers."
  ([client base-url method path] (json-api-caller client base-url method path {}))
  ([client base-url method path opts]
   (let [throw-body (:throw-body opts)
         opts (cond-> opts
                (not (contains? opts :headers)) (assoc :headers {})
                true (assoc-in [:headers "Accept"] "application/json")
                (contains? opts :body) (assoc-in [:headers "Content-Type"] "application/json")
                (contains? opts :body) (update-in [:body] json/generate-string)
                ;; We'll throw after parsing here.
                 true (dissoc :throw-body)
                (:throw-body opts) (assoc :status-errors false))
         response (api-caller client base-url method path opts)
         parsed-body (try
                       (-> (json/parse-string (:body response))
                           keywordize-keys)
                       (catch com.fasterxml.jackson.core.JsonParseException e
                         (throw+ {:kind :puppetlabs.rbac-client/json-parse-error
                                  :msg (format "Invalid JSON body: %s" (:body response))})))]
     (when (and throw-body (http-error? response))
       (throw+ (update-in parsed-body [:kind] keyword)))
     (assoc response :body parsed-body))))