(ns metabase.driver.sync
  "The logic for doing DB and Table syncing itself."
  (:require [cheshire.core :as json]
            [clojure.math.numeric-tower :as math]
            (clojure [set :as set]
                     [string :as s])
            [clojure.tools.logging :as log]
            [korma.core :as k]
            [medley.core :as m]
            [metabase.db.metadata-queries :as queries]
            [metabase.db :refer :all]
            [metabase.driver :as driver]
            [metabase.driver.query-processor :as qp]
            [metabase.driver :as driver]
            [metabase.events :as events]
            (metabase.models [common :as common]
                             [field :refer [Field] :as field]
                             [field-values :as field-values]
                             [foreign-key :refer [ForeignKey]]
                             [table :refer [Table]])
            [metabase.util :as u]))

(declare auto-assign-field-special-type-by-name!
         mark-category-field-or-update-field-values!
         mark-json-field!
         mark-no-preview-display-field!
         mark-url-field!
         set-field-display-name-if-needed!
         sync-database-active-tables!
         sync-database-with-tracking!
         sync-field!
         sync-metabase-metadata-table!
         sync-table-active-fields-and-pks!
         sync-table-fks!
         sync-table-fields-metadata!
         update-table-display-name!
         sync-field-nested-fields!
         update-table-row-count!)


;;; ## ---------------------------------------- PUBLIC API ----------------------------------------


(defn sync-database!
  "Sync DATABASE and all its Tables and Fields."
  [driver database & {:keys [full-sync?]
                      :or {full-sync? true}}]
  (binding [qp/*disable-qp-logging* true
            *sel-disable-logging*   true]
    (driver/sync-in-context driver database (partial sync-database-with-tracking! driver database full-sync?))))

(defn sync-table!
  "Sync a *single* TABLE by running all the sync steps for it.
   This is used *instead* of `sync-database!` when syncing just one Table is desirable."
  [driver table & {:keys [full-sync?]
                   :or {full-sync? true}}]
  (binding [qp/*disable-qp-logging* true]
    (driver/sync-in-context driver @(:db table) (fn []
                                                  ;; TODO: create/update/fetch table
                                                  (sync-database-active-tables! driver [table] :analyze? full-sync?)
                                                  (events/publish-event :table-sync {:table_id (:id table)})))))


;;; ## ---------------------------------------- IMPLEMENTATION ----------------------------------------


(defn- mark-inactive-tables!
  "Mark any `Tables` that are no longer active as such. These are ones that exist in the DB but didn't come back from `active-tables`."
  [database active-tables existing-table->id]
  (doseq [[{table :name, schema :schema, :as table} table-id] existing-table->id]
    (when-not (contains? (set (map :name active-tables)) table)
      (upd Table table-id :active false)
      (log/info (u/format-color 'cyan "Marked table %s.%s%s as inactive." (:name database) (if schema (str schema \.) "") table))

      ;; We need to mark driver Table's Fields as inactive so we don't expose them in UI such as FK selector (etc.)
      (k/update Field
                (k/where {:table_id table-id})
                (k/set-fields {:active false})))))

(defn- create-new-tables!
  "Create new `Tables` as needed. These are ones that came back from `active-tables` but don't already exist in the DB."
  [database active-tables existing-table->id]
  (let [active-tables   (set (for [table active-tables]
                               (-> (select-keys table [:name :schema])
                                   (update :schema identity)))) ; convert tables that come back like {:name ...} to {:name ..., :schema nil}
        existing-tables (set (keys existing-table->id))
        new-tables      (->> (set/difference active-tables existing-tables)
                             ;; exclude _metabase_metadata table which is not a real table
                             (filter #(not= "_metabase_metadata" (s/lower-case (:name %)))))]
    (when (seq new-tables)
      (log/debug (u/format-color 'blue "Found new tables: %s" (vec (for [{table :name, schema :schema} new-tables]
                                                                     (if schema
                                                                       (str schema \. table)
                                                                       table)))))
      (doseq [{table :name, schema :schema} new-tables]
        (ins Table :db_id (:id database), :active true, :schema schema, :name table)))))

(defn- sync-metabase-metadata-table!
  "Databases may include a table named `_metabase_metadata` (case-insentive) which includes descriptions or other metadata about the `Tables` and `Fields`
   it contains. This table is *not* synced normally, i.e. a Metabase `Table` is not created for it. Instead, *this* function is called, which reads the data it
   contains and updates the relevant Metabase objects.

   The table should have the following schema:

     column  | type    | example
     --------+---------+-------------------------------------------------
     keypath | varchar | \"products.created_at.description\"
     value   | varchar | \"The date the product was added to our catalog.\"

   `keypath` is of the form `table-name.key` or `table-name.field-name.key`, where `key` is the name of some property of `Table` or `Field`.

   This functionality is currently only used by the Sample Dataset. In order to use this functionality, drivers must implement optional fn `:table-rows-seq`."
  [driver database active-tables]
  (doseq [{table-name :name} active-tables]
    (when (= (s/lower-case table-name) "_metabase_metadata")
      (doseq [{:keys [keypath value]} (driver/table-rows-seq driver database table-name)]
        (let [[_ table-name field-name k] (re-matches #"^([^.]+)\.(?:([^.]+)\.)?([^.]+)$" keypath)]
          (try (when (not= 1 (if field-name
                               (k/update Field
                                         (k/where {:name field-name, :table_id (k/subselect Table
                                                                                            (k/fields :id)
                                                                                            (k/where {:db_id (:id database), :name table-name}))})
                                         (k/set-fields {(keyword k) value}))
                               (k/update Table
                                         (k/where {:name table-name, :db_id (:id database)})
                                         (k/set-fields {(keyword k) value}))))
                 (log/error (u/format-color "Error syncing _metabase_metadata: no matching keypath: %s" keypath)))
               (catch Throwable e
                 (log/error (u/format-color 'red "Error in _metabase_metadata: %s" (.getMessage e))))))))))

(defn- sync-database-with-tracking! [driver database full-sync?]
  (let [start-time (System/currentTimeMillis)
        tracking-hash (str (java.util.UUID/randomUUID))]
    (log/info (u/format-color 'magenta "Syncing %s database '%s'..." (name driver) (:name database)))
    (events/publish-event :database-sync-begin {:database_id (:id database) :custom_id tracking-hash})

    (let [database-schema    (driver/describe-database driver database)
          active-tables      (:tables database-schema)
          existing-table->id (into {} (for [{:keys [name schema id]} (sel :many :fields [Table :name :schema :id], :db_id (:id database), :active true)]
                                        {{:name name, :schema schema} id}))]
      ;; do some quick validations that our tables list is sensible
      (when-not (and (set? active-tables)
                     (every? map? active-tables)
                     (every? :name active-tables))
        (throw (Exception. "Invalid results returned by active-tables. Results should be a set of maps like {:name \"table_name\", :schema \"schema_name_or_nil\"}.")))

      ;; now persist the tables, creating new ones as needed and inactivating old ones
      (mark-inactive-tables! database active-tables existing-table->id)
      (create-new-tables!    database active-tables existing-table->id)

      ;; once the tables are persisted then we can do a detailed sync for each table
      (let [tables (for [table (sel :many Table, :db_id (:id database) :active true)]
                     ;; replace default delays with ones that reuse database (and don't require a DB call)
                     (assoc table :db (delay database)))]
        (sync-database-active-tables! driver tables :analyze? full-sync?))

      ;; lastly, if we have a _metabase_metadata table go ahead and handle it
      (sync-metabase-metadata-table! driver database active-tables))

    (events/publish-event :database-sync-end {:database_id (:id database) :custom_id tracking-hash :running_time (- (System/currentTimeMillis) start-time)})
    (log/info (u/format-color 'magenta "Finished syncing %s database '%s'. (%d ms)" (name driver) (:name database)
                              (- (System/currentTimeMillis) start-time)))))


(def ^:private sync-progress-meter-string
  "Create a string that shows sync progress for a database.

     (sync-progress-meter-string 10 40)
       -> \"[************······································] 25%\""
  (let [^:const meter-width    50
        ^:const progress-emoji ["😱"  ; face screaming in fear
                                "😢"  ; crying face
                                "😞"  ; disappointed face
                                "😒"  ; unamused face
                                "😕"  ; confused face
                                "😐"  ; neutral face
                                "😬"  ; grimacing face
                                "😌"  ; relieved face
                                "😏"  ; smirking face
                                "😋"  ; face savouring delicious food
                                "😊"  ; smiling face with smiling eyes
                                "😍"  ; smiling face with heart shaped eyes
                                "😎"] ; smiling face with sunglasses
        percent-done->emoji    (fn [percent-done]
                                 (progress-emoji (int (math/round (* percent-done (dec (count progress-emoji)))))))]
    (fn [tables-finished total-tables]
      (let [percent-done (float (/ tables-finished total-tables))
            filleds      (int (* percent-done meter-width))
            blanks       (- meter-width filleds)]
        (str "["
             (apply str (repeat filleds "*"))
             (apply str (repeat blanks "·"))
             (format "] %s  %3.0f%%" (percent-done->emoji percent-done) (* percent-done 100.0)))))))


(defn- sync-database-active-table!
  [driver table & {:keys [analyze?]
                   :or {analyze? true}}]
  (let [table-def (driver/describe-table driver table)]
    ;; make sure table has :display_name
    (u/try-apply update-table-display-name! table)

    ;; create all the Fields / PKs
    (u/try-apply sync-table-active-fields-and-pks! table table-def)

    ;; If we are doing a FULL sync then call functions which require querying the table
    (when analyze?
      (when-let [table-stats (driver/analyze-table driver table)]
        ;; update the row counts for every Table
        (u/try-apply update-table-row-count! table)

        ;; do field level analysis which requires row data
        (sync-table-fields-metadata! driver table)))))


(defn- sync-database-active-tables!
  "Sync active tables by running each of the sync table steps.
   Note that we want to completely finish each step for *all* tables before starting the next, since they depend on the results of the previous step.
   (e.g., `sync-table-fks!` can't run until all tables have finished `sync-table-active-fields-and-pks!`, since creating `ForeignKeys` to `Fields` of *other*
   Tables can't take place before they exist."
  [driver active-tables & {:keys [analyze?]
                           :or {analyze? true}}]
  (let [active-tables (sort-by :name active-tables)]
    ;; Do a first pass which does the bulk of the work
    (let [tables-count          (count active-tables)
          finished-tables-count (atom 0)]
      (doseq [table active-tables]
        (sync-database-active-table! driver table :analyze? analyze?)

        (swap! finished-tables-count inc)
        (log/debug (u/format-color 'magenta "%s Synced table '%s'." (sync-progress-meter-string @finished-tables-count tables-count) (:name table)))))

    ;; Second pass to sync FKs, which must take place after all other table info is in place
    (when (contains? (driver/features driver) :foreign-keys)
      (doseq [table active-tables]
        (u/try-apply sync-table-fks! driver table)))))


;; ## sync-table steps.

;; ### 1) update-table-display-name!

(defn- update-table-display-name!
  "Update the display_name of TABLE if it doesn't exist."
  [table]
  {:pre [(integer? (:id table))]}
  (try
    (when (nil? (:display_name table))
      (upd Table (:id table) :display_name (common/name->human-readable-name (:name table))))
    (catch Throwable e
      (log/error (u/format-color 'red "Unable to update display_name for %s: %s" (:name table) (.getMessage e))))))


;; ### 2) sync-table-active-fields-and-pks!

(def ^{:arglists '([field-def])}
infer-field-special-type
  "If FIELD has a `name` and `base_type` that matches a known pattern, return the `special_type` we should assign to it."
  (let [bool-or-int #{:BooleanField :BigIntegerField :IntegerField}
        float       #{:DecimalField :FloatField}
        int-or-text #{:BigIntegerField :IntegerField :CharField :TextField}
        text        #{:CharField :TextField}
        ;; tuples of [pattern set-of-valid-base-types special-type
        ;; * Convert field name to lowercase before matching against a pattern
        ;; * consider a nil set-of-valid-base-types to mean "match any base type"
        pattern+base-types+special-type [[#"^.*_lat$"       float       :latitude]
                                         [#"^.*_lon$"       float       :longitude]
                                         [#"^.*_lng$"       float       :longitude]
                                         [#"^.*_long$"      float       :longitude]
                                         [#"^.*_longitude$" float       :longitude]
                                         [#"^.*_rating$"    int-or-text :category]
                                         [#"^.*_type$"      int-or-text :category]
                                         [#"^.*_url$"       text        :url]
                                         [#"^_latitude$"    float       :latitude]
                                         [#"^active$"       bool-or-int :category]
                                         [#"^city$"         text        :city]
                                         [#"^country$"      text        :country]
                                         [#"^countryCode$"  text        :country]
                                         [#"^currency$"     int-or-text :category]
                                         [#"^first_name$"   text        :name]
                                         [#"^full_name$"    text        :name]
                                         [#"^gender$"       int-or-text :category]
                                         [#"^last_name$"    text        :name]
                                         [#"^lat$"          float       :latitude]
                                         [#"^latitude$"     float       :latitude]
                                         [#"^lon$"          float       :longitude]
                                         [#"^lng$"          float       :longitude]
                                         [#"^long$"         float       :longitude]
                                         [#"^longitude$"    float       :longitude]
                                         [#"^name$"         text        :name]
                                         [#"^postalCode$"   int-or-text :zip_code]
                                         [#"^postal_code$"  int-or-text :zip_code]
                                         [#"^rating$"       int-or-text :category]
                                         [#"^role$"         int-or-text :category]
                                         [#"^sex$"          int-or-text :category]
                                         [#"^state$"        text        :state]
                                         [#"^status$"       int-or-text :category]
                                         [#"^type$"         int-or-text :category]
                                         [#"^url$"          text        :url]
                                         [#"^zip_code$"     int-or-text :zip_code]
                                         [#"^zipcode$"      int-or-text :zip_code]]]
    ;; Check that all the pattern tuples are valid
    (doseq [[name-pattern base-types special-type] pattern+base-types+special-type]
      (assert (= (type name-pattern) java.util.regex.Pattern))
      (assert (every? (partial contains? field/base-types) base-types))
      (assert (contains? field/special-types special-type)))

    (fn [{:keys [base-type special-type pk?] field-name :name}]
      {:pre [(string? field-name)
             (keyword? base-type)]}
      (or special-type
          (when pk? :id)
          (when (= "id" (s/lower-case field-name)) :id)
          (when-let [matching-pattern (m/find-first (fn [[name-pattern valid-base-types _]]
                                                      (and (or (nil? valid-base-types)
                                                               (contains? valid-base-types base-type))
                                                           (re-matches name-pattern (s/lower-case field-name))))
                                                    pattern+base-types+special-type)]
            ;; a little something for the app log
            (log/debug (u/format-color 'green "%s '%s' matches '%s'. Setting special_type to '%s'."
                                       (name base-type) field-name (first matching-pattern) (name (last matching-pattern))))
            ;; the actual special-type is the last element of the pattern
            (last matching-pattern))))))

(defn- insert-or-update-active-field!
  [field-def existing-field table]
  (let [{field-name :name, field-base-type :base-type}  field-def
        {existing-base-type    :base_type
         existing-special-type :special_type
         existing-display-name :display_name}           existing-field
        field-special-type                              (or existing-special-type
                                                            (infer-field-special-type field-def))
        field-display-name                              (or existing-display-name
                                                            (common/name->human-readable-name field-name))
        field-base-type                                 (if (= field-base-type existing-base-type)
                                                          existing-base-type
                                                          field-base-type)]
    (if-not existing-field
      ;; Field doesn't exist, so create it.
      (ins Field
        :table_id     (:id table)
        :name         field-name
        :display_name field-display-name
        :base_type    field-base-type
        :special_type field-special-type)
      ;; Otherwise update the Field if needed
      (when-not (and (= field-display-name existing-display-name)
                     (= field-base-type existing-base-type)
                     (= field-special-type existing-special-type))
        (log/debug (u/format-color 'blue "Updating field '%s.%s' :base_type %s, :special_type %s, :display_name." (:name table) field-name field-base-type field-special-type field-display-name))
        (upd Field (:id existing-field)
          :display_name field-display-name
          :base_type    field-base-type
          :special_type field-special-type)))))

(defn- sync-table-active-fields-and-pks!
  "Create new Fields (and mark old ones as inactive) for TABLE, and update PK fields."
  [table table-def]

  (assert (every? map? (:fields table-def)) "table-def should describe each field using a map.")
  (assert (every? string? (map :name (:fields table-def))) "The :name of each field in table-def should be a string.")
  (assert (every? (partial contains? field/base-types) (map :base-type (:fields table-def))) "The :base-type of each field in table-def should be a valid Field base type.")

  (let [existing-field-name->field (sel :many :field->fields [Field :name :base_type :special_type :display_name :id], :table_id (:id table), :active true, :parent_id nil)]
    ;; As above, first mark inactive Fields
    (let [active-column-names (set (map :name (:fields table-def)))]
      (doseq [[field-name {field-id :id}] existing-field-name->field]
        (when-not (contains? active-column-names field-name)
          (upd Field field-id :active false)
          (log/info (u/format-color 'cyan "Marked field '%s.%s' as inactive." (:name table) field-name)))))

    ;; Create new Fields, update existing types if needed
    ;; TODO - we need to add functionality to update nested Field base types as well!
    (let [existing-field-names (set (keys existing-field-name->field))
          new-field-names      (set/difference (set (map :name (:fields table-def))) existing-field-names)]
      (when (seq new-field-names)
        (log/debug (u/format-color 'blue "Found new fields for table '%s': %s" (:name table) new-field-names)))

      (doseq [field-def (:fields table-def)]
        (insert-or-update-active-field! field-def (existing-field-name->field (:name field-def)) table)))))


;; ### 3) update-table-row-count!  (full sync ONLY)

(defn- update-table-row-count!
  "Update the row count of TABLE if it has changed."
  [table]
  {:pre [(integer? (:id table))]}
  (try
    (let [table-row-count (queries/table-row-count table)]
      (when-not (= (:rows table) table-row-count)
        (upd Table (:id table) :rows table-row-count)))
    (catch Throwable e
      (log/error (u/format-color 'red "Unable to update row_count for '%s': %s" (:name table) (.getMessage e))))))


;; ### 4) sync-table-fields-metadata!  (full sync ONLY)

(defn- sync-table-fields-metadata!
  "Call `sync-field!` for every active Field for TABLE."
  [driver table]
  {:pre [(map? table)]}
  (let [active-fields (sel :many Field, :table_id (:id table), :active true, :parent_id nil, (k/order :name))]
    (doseq [field active-fields]
      ;; replace the normal delay for the Field with one that just returns the existing Table so we don't need to re-fetch
      (u/try-apply sync-field! driver (assoc field :table (delay table))))))


;; ### 5) sync-table-fks!

(defn- determine-fk-type
  "Determine whether a FK is `:1t1`, or `:Mt1`.
   Do this by getting the count and distinct counts of source `Field`.

   *  If count and distinct count are equal, we have a one-to-one foreign key relationship.
   *  If count is > distinct count, we have a many-to-one foreign key relationship."
  [field]
  (let [field-count          (queries/field-count field)
        field-distinct-count (queries/field-distinct-count field)]
    (if (= field-count field-distinct-count) :1t1
                                             :Mt1)))

(defn- sync-table-fks! [driver table]
  (when (contains? (driver/features driver) :foreign-keys)
    (let [fks (driver/describe-table-fks driver table)]
      (assert (and (set? fks)
                   (every? map? fks)
                   (every? :fk-column-name fks)
                   (every? :dest-table fks)
                   (every? :dest-column-name fks))
              "table-fks should return a set of maps with keys :fk-column-name, :dest-table, and :dest-column-name.")
      (when (seq fks)
        (let [fk-name->id    (sel :many :field->id [Field :name], :table_id (:id table), :name [in (map :fk-column-name fks)], :parent_id nil)]
          (doseq [{:keys [fk-column-name dest-column-name dest-table]} fks]
            (when-let [fk-column-id (fk-name->id fk-column-name)]
              (when-let [dest-table-id (sel :one :field [Table :id], :db_id (:db_id table) :name (:name dest-table) :schema (:schema dest-table))]
                (when-let [dest-column-id (sel :one :id Field, :table_id dest-table-id, :name dest-column-name, :parent_id nil)]
                  (log/debug (u/format-color 'green "Marking foreign key '%s.%s' -> '%s.%s'." (:name table) fk-column-name (:name dest-table) dest-column-name))
                  (ins ForeignKey
                    :origin_id      fk-column-id
                    :destination_id dest-column-id
                    ;; TODO: do we even care about this?
                    ;:relationship   (determine-fk-type {:id fk-column-id, :table (delay table)}) ; fake a Field instance
                    :relationship   :Mt1)
                  (upd Field fk-column-id :special_type :fk))))))))))


;; ## sync-field

(defn- sync-field!
  "Sync the metadata for FIELD, marking urls, categories, etc. when applicable."
  [driver field]
  {:pre [driver field]}
  (loop [field field, [f & more] [(partial driver/driver-specific-sync-field! driver)
                                  (partial mark-url-field! driver)
                                  (partial mark-no-preview-display-field! driver)
                                  mark-category-field-or-update-field-values!
                                  (partial mark-json-field! driver)
                                  (partial sync-field-nested-fields! driver)]]
    (let [field (or (u/try-apply f field)
                    field)]
      (when (seq more)
        (recur field more)))))


;; Each field-syncing function below should return FIELD with any updates that we made, or nil.
;; That way the next fn in the 'pipeline' won't trample over changes made by the last.


;; ### mark-url-field!

(def ^:const ^:private percent-valid-url-threshold
  "Fields that have at least this percent of values that are valid URLs should be marked as `special_type = :url`."
  0.95)

(defn- mark-url-field!
  "If FIELD is texual, doesn't have a `special_type`, and its non-nil values are primarily URLs, mark it as `special_type` `url`."
  [driver field]
  (when (and (not (:special_type field))
             (contains? #{:CharField :TextField} (:base_type field)))
    (when-let [percent-urls (driver/field-percent-urls driver field)]
      (assert (float? percent-urls))
      (assert (>= percent-urls 0.0))
      (assert (<= percent-urls 100.0))
      (when (> percent-urls percent-valid-url-threshold)
        (log/debug (u/format-color 'green "Field '%s' is %d%% URLs. Marking it as a URL." @(:qualified-name field) (int (math/round (* 100 percent-urls)))))
        (upd Field (:id field) :special_type :url)
        (assoc field :special_type :url)))))


;; ### mark-category-field-or-update-field-values!

(def ^:const low-cardinality-threshold
  "Fields with less than this many distinct values should automatically be marked with `special_type = :category`."
  40)

(defn- mark-category-field!
  "If FIELD doesn't yet have a `special_type`, and has low cardinality, mark it as a category."
  [field]
  (let [cardinality (queries/field-distinct-count field low-cardinality-threshold)]
    (when (and (> cardinality 0)
               (< cardinality low-cardinality-threshold))
      (log/debug (u/format-color 'green "Field '%s' has %d unique values. Marking it as a category." @(:qualified-name field) cardinality))
      (upd Field (:id field) :special_type :category)
      (assoc field :special_type :category))))

(defn- mark-category-field-or-update-field-values!
  "If FIELD doesn't yet have a `special_type` and isn't very long (i.e., `preview_display` is `true`), call `mark-category-field!`
   to (possibly) mark it as a `:category`. Otherwise if FIELD is already a `:category` update its `FieldValues`."
  [field]
  (cond
    (and (not (:special_type field))
         (:preview_display field))                       (mark-category-field! field)
    (field-values/field-should-have-field-values? field) (do (field-values/update-field-values! field)
                                                             field)))


;; ### mark-no-preview-display-field!

(def ^:const ^:private average-length-no-preview-threshold
  "Fields whose values' average length is greater than this amount should be marked as `preview_display = false`."
  50)

(defn- mark-no-preview-display-field!
  "If FIELD's is textual and its average length is too great, mark it so it isn't displayed in the UI."
  [driver field]
  (when (and (:preview_display field)
             (contains? #{:CharField :TextField} (:base_type field)))
    (let [avg-len (driver/field-avg-length driver field)]
      (assert (integer? avg-len) "field-avg-length should return an integer.")
      (when (> avg-len average-length-no-preview-threshold)
        (log/debug (u/format-color 'green "Field '%s' has an average length of %d. Not displaying it in previews." @(:qualified-name field) avg-len))
        (upd Field (:id field) :preview_display false)
        (assoc field :preview_display false)))))


;; ### mark-json-field!

(defn- values-are-valid-json?
  "`true` if at every item in VALUES is `nil` or a valid string-encoded JSON dictionary or array, and at least one of those is non-nil."
  [values]
  (try
    (loop [at-least-one-non-nil-value? false, [val & more] values]
      (cond
        (and (not val)
             (not (seq more))) at-least-one-non-nil-value?
        (s/blank? val)         (recur at-least-one-non-nil-value? more)
        ;; If val is non-nil, check that it's a JSON dictionary or array. We don't want to mark Fields containing other
        ;; types of valid JSON values as :json (e.g. a string representation of a number or boolean)
        :else                  (let [val (json/parse-string val)]
                                 (when (not (or (map? val)
                                                (sequential? val)))
                                   (throw (Exception.)))
                                 (recur true more))))
    (catch Throwable _
      false)))

(defn- mark-json-field!
  "Mark FIELD as `:json` if it's textual, doesn't already have a special type, the majority of it's values are non-nil, and all of its non-nil values
   are valid serialized JSON dictionaries or arrays."
  [driver field]
  (when (and (not (:special_type field))
             (contains? #{:CharField :TextField} (:base_type field))
             (values-are-valid-json? (->> (driver/field-values-lazy-seq driver field)
                                          (take driver/max-sync-lazy-seq-results))))
    (log/debug (u/format-color 'green "Field '%s' looks like it contains valid JSON objects. Setting special_type to :json." @(:qualified-name field)))
    (upd Field (:id field) :special_type :json, :preview_display false)
    (assoc field :special_type :json, :preview_display false)))


;; ### sync-field-nested-fields!

(defn- sync-field-nested-fields! [driver field]
  (when (and (= (:base_type field) :DictionaryField)
             (contains? (driver/features driver) :nested-fields))
    (let [nested-field-name->type (driver/active-nested-field-name->type driver field)]
      ;; fetch existing nested fields
      (let [existing-nested-field-name->id (sel :many :field->id [Field :name], :table_id (:table_id field), :active true, :parent_id (:id field))]

        ;; mark existing nested fields as inactive if they didn't come back from active-nested-field-name->type
        (doseq [[nested-field-name nested-field-id] existing-nested-field-name->id]
          (when-not (contains? (set (map keyword (keys nested-field-name->type))) (keyword nested-field-name))
            (log/info (u/format-color 'cyan "Marked nested field '%s.%s' as inactive." @(:qualified-name field) nested-field-name))
            (upd Field nested-field-id :active false)))

        ;; OK, now create new Field objects for ones that came back from active-nested-field-name->type but *aren't* in existing-nested-field-name->id
        (doseq [[nested-field-name nested-field-type] nested-field-name->type]
          (when-not (contains? (set (map keyword (keys existing-nested-field-name->id))) (keyword nested-field-name))
            (log/debug (u/format-color 'blue "Found new nested field: '%s.%s'" @(:qualified-name field) (name nested-field-name)))
            (let [nested-field (ins Field, :table_id (:table_id field), :parent_id (:id field), :name (name nested-field-name) :base_type (name nested-field-type), :active true)]
              ;; Now recursively sync this nested Field
              ;; Replace parent so deref doesn't need to do a DB call
              (sync-field! driver (assoc nested-field :parent (delay field))))))))))
