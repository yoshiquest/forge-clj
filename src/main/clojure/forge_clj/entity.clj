(ns forge-clj.entity
  "Contains macros and functions related to entities and extended entity properties."
  (:require
    [forge-clj.nbt :refer [read-tag-data! write-tag-data!]]
    [forge-clj.core :refer [defassocclass get-data]]
    [forge-clj.util :refer [get-fullname with-prefix remote? get-extended-properties get-entity-by-id get-entity-id deep-merge]]
    [forge-clj.network :refer [fc-network-send fc-network-receive]]
    [clojure.core.async :refer [>!! >! <! chan sub go timeout]]
    [clojure.string :as string])
  (:import
    [net.minecraftforge.common IExtendedEntityProperties]
    [net.minecraft.entity EntityLiving EntityCreature SharedMonsterAttributes]
    [net.minecraft.entity.ai.attributes IAttributeInstance]))

;Creates a class used to store extended properties.
(defmacro defextendedproperties
  "DEFASSOCCLASS: Creates a class implementing IExtendedEntityProperties.

  The following keywords are treated specially:

  :on-load - called after loading nbt data, with an instance of this passed to it.
  :on-save - called before saving nbt data, with an instance of this passed to it."
  [class-name & args]
  (let [classdata (apply hash-map args)
        name-ns (:ns classdata *ns*)
        classdata (assoc classdata :interfaces (conj (:interfaces classdata []) `IExtendedEntityProperties))
        prefix (str class-name "-")
        fullname (get-fullname name-ns class-name)
        this-sym (with-meta 'this {:tag fullname})
        on-load (:on-load classdata `(constantly nil))
        on-save (:on-save classdata `(constantly nil))
        sync-data (:sync-data classdata [])
        dont-save (conj (:dont-save classdata []) :world :entity)
        classdata (dissoc classdata :on-load :on-save :sync-data :dont-save)
        event-base (str (string/replace (str name-ns) #"\." "-") "-" class-name "-")
        server-sync-event (keyword (str event-base "server-sync-event"))
        client-sync-event (keyword (str event-base "client-sync-event"))
        init-sync-event (keyword (str event-base "init-sync-event"))
        on-change `(fn [~'this ~'obj-key ~'obj-val]
                     (when (some #{~'obj-key} ~sync-data)
                       (if (remote? (:world ~'this))
                         (>!! fc-network-send {:key ~'obj-key
                                               :val ~'obj-val
                                               :send :server
                                               :entity-id (get-entity-id (:entity ~'this))
                                               :id ~server-sync-event})
                         (>!! fc-network-send {:key ~'obj-key
                                               :val ~'obj-val
                                               :send :all
                                               :entity-id (get-entity-id (:entity ~'this))
                                               :id ~client-sync-event}))))
        classdata (if (and (not (:on-change classdata)) (not (empty? sync-data)))
                    (assoc classdata :on-change on-change)
                    classdata)]
    `(do
       (defassocclass ~class-name ~classdata)
       ~(when (not (empty? sync-data))
          `(do
             (let [client-sub# (sub fc-network-receive ~client-sync-event (chan))]
               (go
                 (while true
                   (let [~'nbt-data (<! client-sub#)
                         ~'world (:world ~'nbt-data)
                         ~'entity (get-entity-by-id ~'world (:entity-id ~'nbt-data))
                         ~this-sym (get-extended-properties ~'entity ~(str class-name))]
                     (swap! (get-data ~this-sym) assoc (:key ~'nbt-data) (:val ~'nbt-data))))))
             (let [server-sub# (sub fc-network-receive ~server-sync-event (chan))]
               (go
                 (while true
                   (let [~'nbt-data (<! server-sub#)
                         ~'world (:world ~'nbt-data)
                         ~'entity (get-entity-by-id ~'world (:entity-id ~'nbt-data))
                         ~this-sym (get-extended-properties ~'entity ~(str class-name))]
                     (swap! (get-data ~this-sym) assoc (:key ~'nbt-data) (:val ~'nbt-data))))))
             (let [init-sub# (sub fc-network-receive ~init-sync-event (chan))]
               (go
                 (while true
                   (let [~'nbt-data (<! init-sub#)
                         ~'nbt-sync-data (select-keys ~'nbt-data ~sync-data)
                         ~'world (:world ~'nbt-data)
                         ~'entity (get-entity-by-id ~'world (:entity-id ~'nbt-data))
                         ~this-sym (get-extended-properties ~'entity ~(str class-name))]
                     (swap! (get-data ~this-sym) deep-merge ~'nbt-sync-data)))))))
       (with-prefix ~prefix
                    (defn ~'loadNBTData [~'this ~'compound]
                      (let [data# (~'.-data ~this-sym)
                            not-saved# (select-keys (deref data#) ~dont-save)]
                        (apply swap! data# dissoc ~dont-save)
                        (read-tag-data! data# ~'compound)
                        (swap! data# merge not-saved#)
                        (when (and (not (empty? ~sync-data)) (:entity ~'this) (not (remote? (:world ~'this))))
                          (let [~'message (assoc (select-keys (deref data#) ~sync-data)
                                            :send :all
                                            :entity-id (get-entity-id (:entity ~'this))
                                            :id ~init-sync-event)]
                            (go
                              (<! (timeout 100))
                              (>! fc-network-send ~'message)))))
                      (~on-load ~this-sym))
                    (defn ~'saveNBTData [~'this ~'compound]
                      (~on-save ~this-sym)
                      (let [data# (~'.-data ~this-sym)
                            not-saved# (select-keys (deref data#) ~dont-save)]
                        (apply swap! data# dissoc ~dont-save)
                        (write-tag-data! data# ~'compound)
                        (swap! data# merge not-saved#)))
                    (defn ~'init [~'this ~'entity ~'world]
                      (swap! (~'.-data ~this-sym) assoc :world ~'world :entity ~'entity)
                      nil)))))

(def shared-monster-attributes-map
  {:max-health SharedMonsterAttributes/maxHealth
   :movement-speed SharedMonsterAttributes/movementSpeed
   :knockback-resistance SharedMonsterAttributes/knockbackResistance
   :follow-range SharedMonsterAttributes/followRange
   :attack-damage SharedMonsterAttributes/attackDamage})

(defn get-attribute [^EntityLiving entity attribute]
  (if (or (= :attack-damage attribute) (not (get shared-monster-attributes-map attribute)))
    (.registerAttribute (.getAttributeMap entity) (get shared-monster-attributes-map attribute attribute)))
  (.getEntityAttribute entity (get shared-monster-attributes-map attribute attribute)))

(defn set-attribute-base [^EntityLiving entity attribute base-val]
  (.setBaseValue ^IAttributeInstance (get-attribute entity attribute) (double base-val)))

(defmacro defmob
  [class-name & args]
  (let [classdata (apply hash-map args)
        name-ns (:ns classdata *ns*)
        prefix (str class-name "-")
        fullname (get-fullname name-ns class-name)
        this-sym (with-meta 'this {:tag fullname})
        on-load (:on-load classdata `(constantly nil))
        on-save (:on-save classdata `(constantly nil))
        sync-data (:sync-data classdata [])
        dont-save (:dont-save classdata [])
        attributes (:attributes classdata {})
        classdata (dissoc classdata :on-load :on-save :sync-data :dont-save :attributes)
        event-base (str (string/replace (str name-ns) #"\." "-") "-" class-name "-")
        server-sync-event (keyword (str event-base "server-sync-event"))
        client-sync-event (keyword (str event-base "client-sync-event"))
        init-sync-event (keyword (str event-base "init-sync-event"))
        on-change `(fn [~'this ~'obj-key ~'obj-val]
                     (when (some #{~'obj-key} ~sync-data)
                       (if (remote? (~'.-worldObj ~this-sym))
                         (>!! fc-network-send {:key ~'obj-key
                                               :val ~'obj-val
                                               :send :server
                                               :entity-id (get-entity-id ~this-sym)
                                               :id ~server-sync-event})
                         (>!! fc-network-send {:key ~'obj-key
                                               :val ~'obj-val
                                               :send :all
                                               :entity-id (get-entity-id ~this-sym)
                                               :id ~client-sync-event}))))
        classdata (if (and (not (:on-change classdata)) (not (empty? sync-data)))
                    (assoc classdata :on-change on-change)
                    classdata)
        classdata (assoc classdata :expose '{readEntityFromNBT superReadEntityFromNBT
                                             writeEntityToNBT superWriteEntityToNBT
                                             applyEntityAttributes superApplyEntityAttributes})]
    `(do
       (defassocclass EntityCreature ~class-name ~classdata)
       ~(when (not (empty? sync-data))
          `(do
             (let [client-sub# (sub fc-network-receive ~client-sync-event (chan))]
               (go
                 (while true
                   (let [~'nbt-data (<! client-sub#)
                         ~'world (:world ~'nbt-data)
                         ~this-sym (get-entity-by-id ~'world (:entity-id ~'nbt-data))]
                     (swap! (get-data ~this-sym) assoc (:key ~'nbt-data) (:val ~'nbt-data))))))
             (let [server-sub# (sub fc-network-receive ~server-sync-event (chan))]
               (go
                 (while true
                   (let [~'nbt-data (<! server-sub#)
                         ~'world (:world ~'nbt-data)
                         ~this-sym (get-entity-by-id ~'world (:entity-id ~'nbt-data))]
                     (swap! (get-data ~this-sym) assoc (:key ~'nbt-data) (:val ~'nbt-data))))))
             (let [init-sub# (sub fc-network-receive ~init-sync-event (chan))]
               (go
                 (while true
                   (let [~'nbt-data (<! init-sub#)
                         ~'nbt-sync-data (select-keys ~'nbt-data ~sync-data)
                         ~'world (:world ~'nbt-data)
                         ~this-sym (get-entity-by-id ~'world (:entity-id ~'nbt-data))]
                     (swap! (get-data ~this-sym) deep-merge ~'nbt-sync-data)))))))
       (with-prefix ~prefix
                    (defn ~'readEntityFromNBT [~'this ~'compound]
                      (~'.superReadEntityFromNBT ~this-sym ~'compound)
                      (let [data# (~'.-data ~this-sym)
                            not-saved# (select-keys (deref data#) ~dont-save)]
                        (apply swap! data# dissoc ~dont-save)
                        (read-tag-data! data# ~'compound)
                        (swap! data# merge not-saved#)
                        (when (not (empty? ~sync-data))
                          (let [~'message (assoc (select-keys (deref data#) ~sync-data)
                                            :send :all
                                            :entity-id (get-entity-id (:entity ~'this))
                                            :id ~init-sync-event)]
                            (go
                              (<! (timeout 100))
                              (>! fc-network-send ~'message)))))
                      (~on-load ~this-sym))
                    (defn ~'writeEntityToNBT [~'this ~'compound]
                      (~'.superWriteEntityToNBT ~this-sym ~'compound)
                      (~on-save ~this-sym)
                      (let [data# (~'.-data ~this-sym)
                            not-saved# (select-keys (deref data#) ~dont-save)]
                        (apply swap! data# dissoc ~dont-save)
                        (write-tag-data! data# ~'compound)
                        (swap! data# merge not-saved#)))
                    (defn ~'applyEntityAttributes [~'this]
                      (~'.superApplyEntityAttributes ~this-sym)
                      (dorun (map (fn [~'entry]
                                    (set-attribute-base ~this-sym (key ~'entry) (val ~'entry))) ~attributes)))))))