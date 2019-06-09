(ns grneat-swarm-ecal-2017.core
  (:gen-class)
  (:use [us.brevis.physics collision core utils]
        [us.brevis.shape box sphere cone]
        [us.brevis core random globals
         utils plot]
        [brevis-utils parameters]
        [us.brevis.evolution roulette]
        [clojure.set])
  (:require [clojure.string :as string]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [fun.grn.core :as grn]
            [us.brevis.physics.utils :as physics]
            [us.brevis.vector :as v]
            [clj-random.core :as random])
  (:import (graphics.scenery SceneryBase PointLight)
           (sc.iview.vector FloatVector3 DoubleVector3 ClearGLVector3 JOMLVector3 Vector3)
           (cleargl GLVector)
           (java.util.function Predicate)))

; If you base research upon this simulation, please reference the following paper:
;
; Harrington, K. and L. Magbunduku, (2017) "Competitive Dynamics in Eco-evolutionary Genetically-Regulated Swarms". In ECAL 2017, to appear.
;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Globals
(swap! params assoc
       :tag (str "user" (System/nanoTime))
       :initial-num-birds 500
       :min-num-birds 500
       ;:initial-bird-grn "/home/kharrington/git/GRNEATSwarmECAL2017/grn_11500.0_age_114.5.grn"

       :child-parent-radius 10
       :initial-bird-energy 1

       :delta-bird-energy 0.005                             ;0.0025
       :delta-collision 0.01
       :delta-consumption 0.1                              ;0.005
       :delta-movement 0.0000
       :delta-proteins 0                                    ; 0.000001
       :delta-food-energy 0.08
       :delta-stationary 0.01

       :food-migration-probability 0.01

       :food-radius 5                                      ;5
       :num-foods 25
       :selection-attribute :age                            ; :age, :energy
       :breed-energy-threshold 1.1

       :initial-food-energy 2
       :min-food-energy 0.1
       :max-food-energy 2
       :max-acceleration 1.0

       :log-interval 25
       :dt 0.5
       :food-position-type :uniform
       :environment-type :uniform
       :final-environment-type :uniform
       :gui true
       :output-directory ""
       ;:variation-trigger 20000 ;; if this is a number, then this is a time threshold. otherwise it should be a predicate (some function testable for true/false)
       :terminate-trigger 40000 ;; if this is a number, then this is a time threshold. otherwise it should be a predicate (some function testable for true/false)
       :start-time (System/nanoTime)
       :screenshot-interval 250

       :output-directory (str (System/getProperty "user.home") java.io.File/separator)

       :num-GRN-inputs 7
       :num-GRN-outputs 11
       :num-GRN-steps 1
       :neighborhood-radius 100                              ;100

       :width 300
       :height 300)

(def slider-keys
  [:delta-bird-energy
   :delta-collision
   :delta-consumption
   :delta-movement
   :delta-proteins
   :delta-food-energy])

(defn make-frame []
  (seesaw/frame
    :title "Brevis Swarm"
    :content
    (mig/mig-panel
      :items [["<html>Slide the sliders to change the parameters for the swarm</html>" "span, growx"]
              [":delta-bird-energy" "gap 10"]
              [(seesaw/slider :value (get-param :delta-bird-energy) :id :delta-bird-energy :min 0 :max 1 :paint-ticks? true :major-tick-spacing 0.01 :paint-labels? true) "span, growx"]
              [":delta-collision" "gap 10"]
              [(seesaw/slider :value (get-param :delta-collision) :id :delta-collision :min 0 :max 1 :paint-ticks? true :major-tick-spacing 0.01 :paint-labels? true) "span, growx"]
              [":delta-consumption" "gap 10"]
              [(seesaw/slider :value (get-param :delta-consumption) :id :delta-consumption :min 0 :max 1 :paint-ticks? true :major-tick-spacing 0.01 :paint-labels? true) "span, growx"]
              [":delta-movement" "gap 10"]
              [(seesaw/slider :value (get-param :delta-movement) :id :delta-movement :min 0 :max 1 :paint-ticks? true :major-tick-spacing 0.01 :paint-labels? true) "span, growx"]
              [":delta-proteins" "gap 10"]
              [(seesaw/slider :value (get-param :delta-proteins) :id :delta-proteins :min 0 :max 1 :paint-ticks? true :major-tick-spacing 0.01 :paint-labels? true) "span, growx"]
              [":delta-food-energy" "gap 10"]
              [(seesaw/slider :value (get-param :delta-food-energy) :id :delta-food-energy :min 0 :max 1 :paint-ticks? true :major-tick-spacing 0.01 :paint-labels? true) "span, growx"]])))
(defn update-weights [root]
  (let [{:keys [delta-bird-energy
                delta-collision delta-consumption delta-movement
                delta-proteins delta-food-energy]} (seesaw/value root)] ; <- Use (value) to get map of values
    (set-param
      :delta-bird-energy delta-bird-energy
      :delta-collision delta-collision
      :delta-consumption delta-consumption
      :delta-movement delta-movement
      :delta-proteins delta-proteins
      :delta-food-energy delta-food-energy)))
; Note: there was an issue with dt in the initial version of this simulation where the physics and energy dts were treated differently

(def screenshot-num (atom 0))

; Statistics
(def dead-birds (atom 0))
(def accumulated-energy (atom (float 0)))
(def feeding-events (atom 0))
(def collision-events (atom 0))
(def dead-before-reproduction (atom 0))
(def sum-reproductions-before-death (atom 0))
(def num-birds-added (atom 0))

(def triggered (atom false))
(def death-queue (atom []))                                 ;; Queue of UIDs of birds to remove
(def speed 25)

(defn get-run-id
  "Return a hash-based run identifier"
  []
  (hash (+ (:start-time @params)
           (- (hash (:random-seed @params)))
           (hash (:tag @params)))))

(defn log-filename
  []
  (string/replace
    (str (:output-directory @params)
         "brevisSwarmControl_" (:start-time @params)
         "_run_" (:run-number @params)
         "_uid_" (get-run-id)
         "_tag_" (:tag @params)
         ".txt")
    ":" ""))

(defn log-string
  "Log a string to the current log filename."
  [s]
  (spit (log-filename) s :append true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Foods

(defn food?
  "Is a thing food?"
  [thing]
  (= (get-type thing) :food))
(defn bird?
  "Is a thing a bird?"
  [thing]
  (= (get-type thing) :bird))

(defn make-food
  "Make a food entity."
  [position]

  (move (assoc (make-real {:type  :food
                           :color (v/vec4 0 1 1 0.5)
                           ;:shape (create-cone (get-param :food-radius) 2)
                           :shape (create-sphere (get-param :food-radius))})
          :energy (:initial-food-energy @params))
        position))

(defn random-food-position
  "Return a random valid location for food."
  []
  (cond (= (:food-position-type @params) :uniform)
        (v/vec3 (- (lrand (get-param :width)) (/ (get-param :width) 2) (- (/ (get-param :food-radius) 2)))
              (+ 45 (lrand 10))
              (- (lrand (get-param :height)) (/ (get-param :height) 2) (- (/ (get-param :food-radius) 2))))
        (= (:food-position-type @params) :circle)
        (let [t (* 2 Math/PI (lrand (/ (get-param :width) 2)))
              u (+ (lrand (/ (get-param :width) 2)) (lrand (/ (get-param :width) 2)))
              r (if (> u (/ (get-param :width) 2)) (- (get-param :width) u) u)]
          (v/vec3 (* r (Math/cos t))
                (+ 45 (lrand 10))
                (* r (Math/sin t))))))

(defn random-food
  "Make a food at a random position."
  []
  (make-food (random-food-position)))

(defn update-food
  "Update a food item."
  [food]
  (let [nbrs (get-neighbor-objects food)
        nbr-birds (filter bird? nbrs)

        food (set-color (if (> (:energy food) (* (get-dt) (:delta-consumption @params)))
                          (assoc food
                            :bird-count (count nbr-birds)
                            :energy (if (> (+ (:energy food)
                                              (* (get-dt) (:delta-food-energy @params)))
                                           (:max-food-energy @params))
                                      (:max-food-energy @params)
                                      (+ (:energy food)
                                         (* (get-dt) (:delta-food-energy @params)))))
                          (assoc food
                            :energy (:min-food-energy @params)))
                        (apply v/vec4 (repeat 4 (:energy food))))]
                        ;(v/vec4 0 (:energy food) 0 1))]
    (if (< (lrand) (* (get-dt) (get-param :food-migration-probability)))
      (move food (random-food-position))
      food)))

(add-update-handler :food update-food)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Birds
(defn random-grn
  "Make a random bird genome."
  []
  (grn/make-grn))
(defn mutate-grn
  "Mutate a bird's genome. Distance is bounded"
  [grn]
  (grn/mutate grn))
(defn random-bird-position
  "Returns a random valid bird position."
  []
  (v/vec3 (- (lrand (get-param :width)) (/ (get-param :width) 2))
        (+ 59.5 (lrand 10))                                 ;; having this bigger than the neighbor radius will help with speed due to neighborhood computation
        (- (lrand (get-param :height)) (/ (get-param :height) 2))))

(defn make-bird
  "Make a new bird with the specified program. At the specified location."
  ([position]
   (make-bird position (random-grn)))
  ([position grn]
   (assoc (move (make-real {:type  :bird
                            :color (v/vec4 (lrand) (lrand) (lrand) 1)
                            :shape (create-cone 5.2 1.5)})
                position)
     :breed-count 0

     :newborn? true
     :d-nbr-bird 0
     :d-nbr-food 0
     :d-centroid 0
     :num-nbr-birds 0
     :num-nbr-foods 0

     :position-history []
     :birth-time (get-time)
     :energy (:initial-bird-energy @params)
     :grn grn)))

(defn load-bird
  "Load a random bird from grn filename"
  [grn-filename]
  ;(println :load-grn (grn/load-from-file grn-filename))
  (make-bird (random-bird-position)
             (grn/load-from-file grn-filename)))

(defn random-bird
  "Make a new random bird."
  []
  (make-bird (random-bird-position)))

(defn bound-acceleration
  "Keeps the acceleration within a reasonable range."
  [v]
  (if (> (v/length-vec3 v) (:max-acceleration @params))
    (v/mul-vec3 (v/div-vec3 v (v/length-vec3 v)) (:max-acceleration @params))
    v))

(defn select-parent
  "Select a parent from candidates, if no candidates supplied, all exising birds is used."
  ([]
   (select-parent (let [candidates (filter #(and (bird? %) (not= (:birth-time %) (get-time))) (all-objects))
                        candidates (if (empty? candidates)
                                     (filter bird? (all-objects))
                                     candidates)]
                    candidates)))
  ([candidates]
   (if (empty? candidates)
     (select-parent)
     (cond (= (get-param :selection-attribute) :energy)
           (first (select-with candidates :energy))
           (= (get-param :selection-attribute) :age)
           (first (select-with candidates #(- (get-time) (:birth-time %))))
           :else
           (lrand-nth candidates)))))

(defn crossover-grns
  "Crossover 2 grns."
  [p1 p2]
  (grn/crossover p1 p2))

(defn breed
  "Breed from a bird, returning an instantiate and placed child."
  [bird p1 nbrs]
  (let [p2 (select-parent nbrs)
        grn (mutate-grn (crossover-grns (:grn p1) (:grn p2)))
        child-position (if (:child-parent-radius @params)
                         (v/add-vec3 (get-position p1)
                                   (v/mul-vec3 (lrand-vec3 -1 1 -1 1 -1 1)
                                               (lrand (:child-parent-radius @params))))
                         (random-bird-position))]
    (set-velocity (set-acceleration
                    (move (assoc bird
                            :reproduced? false
                            :position-history []
                            :birth-time (get-time)
                            :energy (/ (:energy p1)
                                       2)
                            :grn grn)
                          child-position)
                    (v/vec3 0 0 0))
                  (v/vec3 0 0 0))))

(defn fly
  "Change the acceleration of a bird."
  [bird]
  (let [bird-pos (get-position bird)

        nbrs (get-neighbor-objects bird)
        nbr-birds (sort-by #(v/length-vec3 (v/sub-vec3 (get-position %) bird-pos))
                           (filter bird? nbrs))
        nbr-foods (sort-by #(v/length-vec3 (v/sub-vec3 (get-position %) bird-pos))
                           (filter food? nbrs))
        ;_ (println :bird-pos bird-pos :nbrs (count nbrs) :birds (count nbr-birds) :foods (count nbr-foods))
        closest-bird (when-not (empty? nbr-birds) (first nbr-birds))
        closest-food (when-not (empty? nbr-foods) (first nbr-foods))
        dclosest-bird (when closest-bird (v/sub-vec3 (get-position closest-bird) bird-pos))
        dclosest-food (when closest-food (v/sub-vec3 (get-position closest-food) bird-pos))
        centroid-pos (if (empty? nbr-birds)
                       (v/vec3 0 0 0)
                       (v/mul-vec3 (apply v/add-vec3
                                          (map get-position nbr-birds))
                                 (/ (count nbr-birds))))
        dcentroid (if (empty? nbr-birds)
                    (v/vec3 0 0 0)
                    (v/sub-vec3 centroid-pos bird-pos))

        speed (v/length-vec3 (get-velocity bird))
        closest-speed (when closest-bird (v/length-vec3 (get-velocity closest-bird)))

        ;; Compute inputs and update
        grn (grn/update-grn
              (grn/set-grn-inputs (:grn bird)
                                  [(if dclosest-bird (/ (v/length-vec3 dclosest-bird) (get-neighborhood-radius)) 1)
                                   (if dclosest-bird (/ (v/length-vec3 dcentroid) (get-neighborhood-radius)) 1)
                                   (if dclosest-food (/ (v/length-vec3 dclosest-food) (get-neighborhood-radius)) 1)
                                   (if (empty? nbr-birds) 0 (/ (count nbr-birds) (get-param :initial-num-birds)))
                                   speed
                                   (or closest-speed 0)
                                   (:energy bird)]))

        ;; Compute outputs
        grn-outputs (grn/get-grn-outputs grn)
        grn-scale 1;0.0
        total-sum 1 #_(apply + grn-outputs)
        grn-outputs (map #(* (/ % total-sum) grn-scale) grn-outputs)
        closest-bird-weight (- (nth grn-outputs 0) (nth grn-outputs 1))
        closest-food-weight (- (nth grn-outputs 2) (nth grn-outputs 3))
        closest-random-weight (nth grn-outputs 4)
        velocity-weight (- (nth grn-outputs 5) (nth grn-outputs 6))
        centroid-weight (- (nth grn-outputs 7) (nth grn-outputs 8))
        closest-velocity-weight (- (nth grn-outputs 9) (nth grn-outputs 10))

        new-acceleration (v/add-vec3 (if closest-bird
                                       (v/mul-vec3 dclosest-bird closest-bird-weight)
                                       (v/vec3 0 0 0))
                                     (if closest-food
                                       (v/mul-vec3 dclosest-food closest-food-weight)
                                       (v/vec3 0 0 0))
                                     (if dclosest-bird
                                       (v/mul-vec3 dcentroid centroid-weight)
                                       (v/vec3 0 0 0))
                                     (if closest-speed
                                       (v/mul-vec3 (get-velocity closest-bird) closest-velocity-weight)
                                       (v/vec3 0 0 0))
                                     (v/mul-vec3 (get-velocity bird) velocity-weight)
                                     #_control-force
                                     (v/mul-vec3 (lrand-vec3 -1 1 -1 1 -1 1) closest-random-weight))

        child (when (> (:energy bird) (get-param :breed-energy-threshold))
                (breed (add-object (random-bird)) bird nbr-birds))
        bird (if (> (:energy bird) (get-param :breed-energy-threshold))
               (assoc bird
                 :energy (/ (:energy bird) 2)
                 :breed-count (inc (:breed-count bird)))
               bird)]

    (if (< (:energy bird) 0)
      (do
        (when (zero? (:breed-count bird))
          (swap! dead-before-reproduction inc))
        (swap! sum-reproductions-before-death #(+ (:breed-count bird) %))
        (swap! dead-birds inc)
        (swap! death-queue conj (get-uid bird))
        bird)
      (assoc (set-acceleration
               (set-color bird
                          (v/vec4 (/ (:energy bird) 10)
                                (/ (:num-proteins grn) 50)
                                (min 1 (/ (- (get-time) (:birth-time bird)) 200))
                                1.0))
               ;bird
               (bound-acceleration
                 new-acceleration))
        :newborn? false
        :grn grn
        :energy (- (:energy bird)
                   (* (get-dt) (:delta-proteins @params) (:num-proteins grn))
                   (* (get-dt) (:delta-bird-energy @params))
                   ;(* (get-dt) (:delta-stationary @params) (min 1 (/ (max 0.001 speed))))
                   (* (get-dt) (:delta-stationary @params) (if (< speed 0.001) 1 0))
                   (* (get-dt) (:delta-movement @params) (v/length-vec3 (get-velocity bird))))

        ; Statistics
        :d-nbr-bird (if closest-bird (v/length-vec3 dclosest-bird) 0)
        :d-nbr-food (if closest-food (v/length-vec3 dclosest-food) 0)
        :d-centroid (if (empty? nbr-birds) 0 (v/length-vec3 dcentroid))
        :num-nbr-birds (count nbr-birds)
        :num-nbr-foods (count nbr-foods)))))

(enable-kinematics-update :bird)                            ; This tells the simulator to move our objects
(add-update-handler :bird fly)                              ; This tells the simulator how to update these objects
;(add-parallel-update-handler :bird fly)                              ; This tells the simulator how to update these objects
(add-global-update-handler 10
                           (fn []
                             (doseq [uid @death-queue]
                               (.deleteObject ^us.brevis.Engine @*java-engine* uid))
                             (reset! death-queue [])))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Global updates

(defn average
  "Calculate an averate."
  [samples]
  (/ (apply + samples) (count samples)))

(defn series-statistics
  "Convenience function to make a mergable map of statistics for some samples."
  [series-name samples]
  {(keyword (str (name series-name) "-average")) (average samples)
   ;(keyword (str (name series-name) "-stddev")) (std-dev samples)
   (keyword (str (name series-name) "-min")) (apply min samples)
   (keyword (str (name series-name) "-max")) (apply max samples)
   (keyword (str (name series-name) "-N")) (count samples)})


(defn current-log-map
  "Return a map with the logged state"
  []
  (let [objs (all-objects)
        foods (filter food? objs)
        ; Filter newborns
        birds (filter #(and (bird? %)
                            (not (:newborn? %)))
                      objs)

        bird-ages (map #(- (get-time) (get % :birth-time)) birds)
        food-energies (map :energy foods)
        bird-energies (map :energy birds)]
    ;(println :objs (count objs) :birds (count birds) :foods (count foods))
    (merge {:t (get-time)
            :wall-time (get-wall-time)
            :num-birds (count birds)
            :num-foods (count foods)
            :num-collisions @collision-events
            :num-feedings @feeding-events
            :num-dead-birds @dead-birds
            :num-dead-before-reproduction @dead-before-reproduction
            :sum-reproductions-before-death @sum-reproductions-before-death
            :num-birds-added @num-birds-added}

           (series-statistics :bird-ages bird-ages)
           (series-statistics :food-energies food-energies)
           (series-statistics :bird-energies bird-energies)
           (series-statistics :breed-count (map :breed-count birds))
           (series-statistics :d-nbr-bird (map :d-nbr-bird birds))
           (series-statistics :d-nbr-food (map :d-nbr-food birds))
           (series-statistics :d-centroid (map :d-centroid birds))
           (series-statistics :num-nbr-birds (map :num-nbr-birds birds))
           (series-statistics :num-nbr-foods (map :num-nbr-foods birds))
           (series-statistics :num-proteins (map #(:num-proteins (:grn %)) birds)))))


(defn current-log-string
  "Return the current string that logs the simulation state."
  [& args]
  (let [log-map (current-log-map)]
    (if (first args)
      (str (string/join "\t" (keys log-map)) "\n"
           (string/join "\t" (map float (vals log-map))))
      (str (string/join "\t" (map float (vals log-map)))))))

; Add a periodic call for logging based on :log-interval
(let [log-counter (atom 0)]
  (add-global-update-handler 1
                             (fn []
                               ; Write interval statistics
                               (when (> (int (/ (get-time) (get-param :log-interval)))
                                        @log-counter)
                                 (log-string (str (current-log-string (zero? @log-counter)) "\n"))
                                 (reset! collision-events 0)
                                 (reset! feeding-events 0)
                                 (reset! dead-birds 0)
                                 (reset! dead-before-reproduction 0)
                                 (reset! sum-reproductions-before-death 0)
                                 (reset! log-counter (int (/ (get-time) (get-param :log-interval))))))))

(defn get-oldest
  []
  (let [birds  (filter bird? (all-objects))
        earliest-time (apply min (map :birth-time birds))]
    (random/lrand-nth (filter #(= (:birth-time %) earliest-time) birds))))

(defn get-oldest-and-richest
  []
  (let [birds  (filter bird? (all-objects))
        rich-fn (fn [b] (* (- (get-time b) (:birth-time b))
                          (:energy b)))
        score (apply max (map rich-fn birds))]
    (random/lrand-nth (filter #(= (rich-fn %) score) birds))))

  ;(random/lrand-nth (min-key :birth-time (into [] (filter bird? (all-objects))))))

(let [grn-counter (atom 0)
      grn-interval 100]
  (add-global-update-handler 1
                             (fn []
                               ; Write interval statistics
                               (when (> (int (/ (get-time) grn-interval))
                                        @grn-counter)
                                 (let [oldest (get-oldest)
                                       filename (str "grn_" (get-time) "_age_" (- (get-time) (:birth-time oldest)) ".grn")]
                                   (doseq [bird (filter bird? (all-objects))]
                                     (when-not (= bird oldest)
                                       (set-object (get-uid bird)
                                         (assoc bird
                                           :grn (crossover-grns (:grn bird) (:grn oldest))))))
                                         ;:grn (mutate-grn (:grn oldest)))))
                                   (println "Saving oldest grn to " filename)
                                   (grn/write-to-file (:grn oldest) filename)
                                   (reset! grn-counter (int (/ (get-time) grn-interval))))))))



; Terminate the simulation when :terminate-trigger passes
; Also has an environmental variation expression
(add-global-update-handler 2
                           (fn [] (let [objs (all-objects)
                                        foods (filter food? objs)]
                                    ;; terminate simulation if need be
                                    (when (and (number? (:terminate-trigger @params))
                                               (> (get-time) (:terminate-trigger @params)))
                                      (swap! *gui-state* assoc :close-requested true))
                                    ;; switch environment types if need be
                                    (cond (and (not @triggered)
                                               (number? (:variation-trigger @params))
                                               (> (get-time) (:variation-trigger @params)))
                                          (do (reset! triggered true)
                                              (swap! params assoc :environment-type (:final-environment-type @params)))))))

; Maintain the minimum number of birds
(add-global-update-handler 2
                           (fn [] (let [objs (all-objects)
                                        birds (filter bird? objs)]
                                    (when (< (count birds) (:min-num-birds @params))
                                      (reset! num-birds-added (- (:initial-num-birds @params) (count birds)))
                                      (dotimes [k (- (:initial-num-birds @params) (count birds))]
                                        (let [bird (random-bird)]
                                          (add-object bird)))))))

; Take periodic screenshots
(add-global-update-handler 3
                           (fn []
                             (when (and (pos? (:screenshot-interval @params))
                                        (> (get-time) (* (:screenshot-interval @params) @screenshot-num)))
                               (.takeScreenshot (fun.imagej.sciview/get-sciview) (str "swarmScenery_t_" (get-time) ".png"))
                               (swap! screenshot-num inc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Collision handling
;;
;; Collision functions take [collider collidee] and return [collider collidee]
;;   Both can be modified; however, two independent collisions are actually computed [a b] and [b a]
;; A bird eats
(add-collision-handler :bird :food
                       (fn [bird food]
                         (if (> (:energy food) (* (get-dt) (:delta-consumption @params)))
                           (do (swap! accumulated-energy #(+ % (:delta-consumption @params)))
                               (swap! feeding-events inc)
                               [(assoc bird
                                  :energy (+ (:energy bird) (* (get-dt) (:delta-consumption @params))))
                                (assoc food
                                  :energy (- (:energy food) (* (get-dt) (:delta-consumption @params))))])
                           [bird food])))

(defn bump
  "Collision between two birds. This is called on [bird1 bird2] and [bird2 bird1] independently
 so we only modify bird1."
  [bird1 bird2]
  (swap! collision-events inc)
  [(assoc (physics/set-color bird1 (v/vec4 (random/lrand) (random/lrand) (random/lrand) 1))
     :energy (- (:energy bird1) (* (get-dt) (:delta-collision @params))))
   (assoc bird2 :energy (- (:energy bird2) (* (get-dt) (:delta-collision @params))))])
(add-collision-handler :bird :bird bump)

(defn surround-lighting
  []
  (let [y 55
        c (GLVector. (float-array [0 45 0]))
        r (get-param :width)
        lights (.getSceneNodes (fun.imagej.sciview/get-sciview)
                               (reify Predicate
                                 (test [_ n]
                                   (instance? PointLight n))))]
    (dotimes [k (count lights)]
        (let [light (aget lights k)
              x (+ (.x c) (* r (Math/cos (if (zero? k) 0 (* Math/PI 2 (/ k (count lights)))))))
              z (+ (.z c) (* r (Math/sin (if (zero? k) 0 (* Math/PI 2 (/ k (count lights)))))))]
          (.setLightRadius light (* 2 r))
          (.setPosition light (GLVector. (float-array [x y z])))))))

(defn center-on-scene
  []
  (let [camera (.getCamera (fun.imagej.sciview/get-sciview))]
    ;(.setPosition camera (GLVector. (float-array [(get-param :width) 65 (get-param :height)])))
    (.setPosition camera (GLVector. (float-array [127.857 191.826 -140.759])))
    (.setTarget camera (GLVector. (float-array [0 45 0])))
    (.setTargeted camera true)
    (.setNeedsUpdate camera true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## brevis control code
(defn initialize-simulation
  "This is the function where you add everything to the world."
  []
  (init-world)
  (init-view)
  (grn/initialize-grneat)
  (swap! us.brevis.globals/*gui-state* assoc :gui (:gui @params))

  ;(.setPosition (:camera @us.brevis.globals/*gui-state*) (vec3 0.0 -385 0))
  ;(.setRotation (:camera @us.brevis.globals/*gui-state*) (vec4 90 0 -90 0))

  (set-dt (:dt @params))
  (set-neighborhood-radius (:neighborhood-radius @params))

  (dotimes [_ (:num-foods @params)]
    (add-object (random-food)))

  (dotimes [_ (:initial-num-birds @params)]
    (if (:initial-bird-grn @params)
      (add-object (load-bird (:initial-bird-grn-filename @params)))
      (add-object (random-bird))))

  (when (get-param :gui)

    (.setVisible (.getFloor (fun.imagej.sciview/get-sciview)) false)
    (center-on-scene)
    (surround-lighting)
    ;(.start (Thread. delay-init))

    (let [root (make-frame)]
      (seesaw/listen (map #(seesaw/select root [%])
                          [:#delta-bird-energy
                           :#delta-collision :#delta-consumption :#delta-movement
                           :#delta-proteins :#delta-food-energy]) :change
                     (fn [e]
                       (update-weights root)))
      (seesaw/invoke-later
        (-> root
            seesaw/pack!
            seesaw/show!)))

    ; Plotting

    (add-plot-handler
      (fn []
        [(* (get-time) (get-dt)) (count (filter bird? (all-objects)))])
      :interval 200
      :title "Num birds")

    #_(add-plot-handler
        (fn []
          [(* (get-time) (get-dt)) (/ @dead-birds (get-time))])
        :interval 200
        :title "Num dead birds")

    #_(add-plot-handler
        (fn []
          [(* (get-time) (get-dt)) (/ @accumulated-energy (get-time))])
        :interval 200
        :title "Accumulated energy")

    #_(add-plot-handler
        (fn []
          [(* (get-time) (get-dt))
           (let [birds (filter bird? (all-objects))]
             (/ (apply + (map :energy birds)) (if (empty? birds) 0 (count birds))))])
        :interval 200
        :title "Average energy")

    #_(add-plot-handler
        (fn []
          [(* (get-time) (get-dt))
           (let [foods (filter food? (all-objects))]
             (/ (apply + (map :energy foods)) (if (empty? foods) 0 (count foods))))])
        :inteval 200
        :title "Food Energy")

    #_(add-plot-handler
        (fn []
          [(* (get-time) (get-dt))
           (let [birds (filter bird? (all-objects))]
             (/ (apply + (map #(:num-proteins (:grn %)) birds)) (if (empty? birds) 0 (count birds))))])
        :interval 200
        :title "Average num. proteins")

    #_(add-plot-handler
        (fn []
          [(* (get-time) (get-dt))
           (let [birth-times (map :birth-time (filter bird? (all-objects)))
                 t (get-time)]
             (/ (apply + (map #(- t %) birth-times)) (if (empty? birth-times) 0 (count birth-times))))])
        :interval 200
        :title "Average age")

    #_(add-multiplot-handler
        :xy-fns [(fn []
                   [(* (get-time) (get-dt))
                    (let [birds (filter bird? (all-objects))]
                      (/ (apply + (map :energy birds)) (if (empty? birds) 0 (count birds))))])
                 (fn []
                   [(* (get-time) (get-dt))
                    (let [birds (filter bird? (all-objects))]
                      (if (empty? birds) 0 (apply max (map :energy birds))))])
                 (fn []
                   [(* (get-time) (get-dt))
                    (let [birds (filter bird? (all-objects))]
                      (if (empty? birds) 0 (apply min (map :energy birds))))])]
        :legends ["Avg" "Max" "Min"]
        :interval 200
        :title "Energy (bird)")

    #_(add-multiplot-handler
        :xy-fns [(fn []
                   [(* (get-time) (get-dt))
                    (let [foods (filter food? (all-objects))]
                      (/ (apply + (map :energy foods)) (if (empty? foods) 0 (count foods))))])
                 (fn []
                   [(* (get-time) (get-dt))
                    (let [foods (filter food? (all-objects))]
                      (if (empty? foods) 0 (apply max (map :energy foods))))])
                 (fn []
                   [(* (get-time) (get-dt))
                    (let [foods (filter food? (all-objects))]
                      (if (empty? foods) 0 (apply min (map :energy foods))))])]
        :legends ["Avg" "Max" "Min"]
        :interval 200
        :title "Energy (food)")

    #_(add-multiplot-handler
        :xy-fns [(fn []
                   [(* (get-time) (get-dt))
                    (let [birth-times (map :birth-time (filter bird? (all-objects)))
                          t (get-time)]
                      (* (get-dt) (/ (apply + (map #(- t %) birth-times)) (if (empty? birth-times) 0 (count birth-times)))))])
                 (fn []
                   [(* (get-time) (get-dt))
                    (let [birth-times (map :birth-time (filter bird? (all-objects)))
                          t (get-time)]
                      (* (get-dt) (if (empty? birth-times) 0 (apply max (map #(- t %) birth-times)))))])
                 (fn []
                   [(* (get-time) (get-dt))
                    (let [birth-times (map :birth-time (filter bird? (all-objects)))
                          t (get-time)]
                      (* (get-dt) (if (empty? birth-times) 0 (apply min (map #(- t %) birth-times)))))])]
        :legends ["Avg" "Max" "Min"]
        :interval 200
        :title "Age")))



(defn -main [& args]
  (SceneryBase/xinitThreads)
  (let [;; First put everything into a map
        argmap (apply hash-map
                      (mapcat #(vector (read-string (first %)) (second %) #_(read-string (second %)))
                              (partition 2 args)))
        ;; Then read-string on *some* args, but ignore others                                                                                                                                                                                                                                                              
        argmap (apply hash-map
                      (apply concat
                             (for [[k v] argmap]
                               [k (cond (= k :output-directory) v
                                        :else (read-string v))])))
        random-seed (cond (:random-seed argmap)
                          (byte-array (map byte (read-string (:random-seed argmap))))
                          (:run-number argmap)
                          (with-rng (make-RNG (generate-random-seed))
                            (dotimes [k (:run-number argmap)] (lrand))
                            (generate-random-seed))
                          :else
                          (generate-random-seed))
        arg-params (merge @params argmap {:random-seed (random-seed-to-string random-seed)})
        rng (make-RNG random-seed)]
    (println argmap)
    (println arg-params)
    (reset! params arg-params)

    ;(System/)

    (doseq [[k v] @params]
      (log-string (str k "\t" (if (string? v) (str "\"" v "\"") v) "\n")))
    (log-string "@DATA\n")
    (with-rng rng
              ((if (:gui @params) start-gui start-nogui)
               initialize-simulation java-update-world))))



;; For autostart with Counterclockwise in Eclipse
;(when
;  (find-ns 'ccw.complete)
;  (-main))

(defn get-argmaps-experiment-combo
  "Return the argmaps for experiment"
  [experiment-name]
  (let [base-params {:output-directory (str "PROBABLY THE SAME DIRECTORY AS YOUR CLUSTER STORAGE FOR THIS PROJECT" experiment-name "/")
                     :gui false
                     :terminate-trigger 15000
                     :delta-bird-energy 0.005                             ;0.0025
                     :delta-collision 0.005
                     :delta-consumption 0.15                              ;0.005
                     :delta-movement 0.0000
                     :delta-proteins 0                                    ; 0.000001
                     :delta-food-energy 0.08
                     :food-migration-probability 0.01
                     :food-radius 5                                      ;5
                     :num-foods 25
                     :selection-attribute :age                            ; :age, :energy
                     :breed-energy-threshold 1.1}]                          ; threshold that triggers spawning

    (for [food-radius [5 10 15]
          num-foods [15 25]
          food-migration-probability [0 0.01 0.001]
          ;food-migration-probability [0.001]
          ;selection-attribute [:age :energy :none]
          delta-collision [0 0.005 0.01 0.015 0.02] 
          selection-attribute [:age :none]
          delta-consumption [0.1]
          delta-bird-energy [0.005]]
          ;delta-consumption [0.05 0.15]
          ;delta-bird-energy [0.005 0.01]

      [(merge base-params
             {:tag (str "params_fR=" food-radius "_nF=" num-foods "_fMP=" food-migration-probability "_dCollision=" delta-collision "_sA=" selection-attribute "_dConsumption=" delta-consumption "_dBE=" delta-bird-energy)
              :food-radius food-radius
              :num-foods num-foods
              :food-migration-probability food-migration-probability
              :delta-collision delta-collision
              :selection-attribute selection-attribute
              :delta-consumption delta-consumption
              :delta-bird-energy delta-bird-energy})])))

(-main)
