(ns grneat-swarm-ecal-2017.core
  (:gen-class)
  (:use [brevis.physics collision core utils]
        [brevis.shape box sphere cone]
        [brevis core vector random globals
           utils plot]
        [brevis-utils parameters]
        [brevis.evolution roulette]
        [clojure.set])
  (:require [clojure.string :as string]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [grneat-swarm-ecal-2017.grn :as grn]
            [grneat-swarm-ecal-2017.rocks :as distributed]            
            #_[brevis.distributed-computing.rocks :as distributed]))

; If you base research upon this simulation, please reference the following paper:
;
; Harrington, K. and L. Magbunduku, (2017) "Competitive Dynamics in Eco-evolutionary Genetically-Regulated Swarms". In ECAL 2017, to appear.
;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Globals
(swap! params assoc
       :tag (str "user" (System/nanoTime))
       :initial-num-birds 100
       :min-num-birds 100

       :child-parent-radius 20
       :initial-bird-energy 1

       :delta-bird-energy 0.005                             ;0.0025
       :delta-collision 0.01
       :delta-consumption 0.1                              ;0.005
       :delta-movement 0.0000
       :delta-proteins 0                                    ; 0.000001
       :delta-food-energy 0.08

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
       :screenshot-interval -1

       :output-directory (str (System/getProperty "user.home") java.io.File/separator)

       :num-GRN-inputs 6
       :num-GRN-outputs 9
       :num-GRN-steps 2
       :neighborhood-radius 50                              ;100

       :width 200
       :height 200)

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
                           :color (vec4 0 1 1 0.5)
                           :shape (create-sphere (get-param :food-radius))})
          :energy (:initial-food-energy @params))
        position))

(defn random-food-position
  "Return a random valid location for food."
  []
  (cond (= (:food-position-type @params) :uniform)
        (vec3 (- (lrand (get-param :width)) (/ (get-param :width) 2) (- (/ (get-param :food-radius) 2)))
              (+ 45 (lrand 10))
              (- (lrand (get-param :height)) (/ (get-param :height) 2) (- (/ (get-param :food-radius) 2))))
        (= (:food-position-type @params) :circle)
        (let [t (* 2 Math/PI (lrand (/ (get-param :width) 2)))
              u (+ (lrand (/ (get-param :width) 2)) (lrand (/ (get-param :width) 2)))
              r (if (> u (/ (get-param :width) 2)) (- (get-param :width) u) u)]
          (vec3 (* r (Math/cos t))
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
                        (vec4 0 (:energy food) 0 0.5))]
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
  (vec3 (- (lrand (get-param :width)) (/ (get-param :width) 2))
        (+ 59.5 (lrand 10))                                 ;; having this bigger than the neighbor radius will help with speed due to neighborhood computation
        (- (lrand (get-param :height)) (/ (get-param :height) 2))))

(defn make-bird
  "Make a new bird with the specified program. At the specified location."
  ([position]
   (make-bird position (random-grn)))
  ([position grn]
   (assoc (move (make-real {:type  :bird
                            :color (vec4 (lrand) (lrand) (lrand) 1)
                            :shape (create-cone 2.2 1.5)})
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
  (make-bird (random-bird-position)
             (grn/load-from-file grn-filename)))

(defn random-bird
  "Make a new random bird."
  []
  (make-bird (random-bird-position)))

(defn bound-acceleration
  "Keeps the acceleration within a reasonable range."
  [v]
  (if (> (length v) (:max-acceleration @params))
    (mul (div v (length v)) (:max-acceleration @params))
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
   (cond (= (get-param :selection-attribute) :energy)
         (first (select-with candidates :energy))
         (= (get-param :selection-attribute) :age)
         (first (select-with candidates #(- (get-time) (:birth-time %))))
         :else
         (lrand-nth candidates))))

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
                         (add-vec3 (get-position p1)
                                   (mul-vec3 (lrand-vec3 -1 1 -1 1 -1 1)
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
                    (vec3 0 0 0))
                  (vec3 0 0 0))))

(defn fly
  "Change the acceleration of a bird."
  [bird]
  (let [bird-pos (get-position bird)

        nbrs (get-neighbor-objects bird)
        nbr-birds (sort-by #(length (sub-vec3 (get-position %) bird-pos))
                           (filter bird? nbrs))
        nbr-foods (sort-by #(length (sub-vec3 (get-position %) bird-pos))
                           (filter food? nbrs))
        closest-bird (when-not (empty? nbr-birds) (first nbr-birds))
        closest-food (when-not (empty? nbr-foods) (first nbr-foods))
        dclosest-bird (when closest-bird (sub-vec3 (get-position closest-bird) bird-pos))
        dclosest-food (when closest-food (sub-vec3 (get-position closest-food) bird-pos))
        centroid-pos (if (empty? nbr-birds)
                       (vec3 0 0 0)
                       (mul-vec3 (apply add-vec3
                                        (map get-position nbr-birds))
                                 (/ (count nbr-birds))))
        dcentroid (if (empty? nbr-birds)
                    (vec3 0 0 0)
                    (sub-vec3 centroid-pos bird-pos))

        speed (length (get-velocity bird))

        ;; Compute inputs and update
        grn (grn/update-grn
              (grn/set-grn-inputs (:grn bird)
                                  [(if dclosest-bird (/ (length dclosest-bird) (get-neighborhood-radius)) 1)
                                   (if dclosest-bird (/ (length dcentroid) (get-neighborhood-radius)) 1)
                                   (if dclosest-food (/ (length dclosest-food) (get-neighborhood-radius)) 1)
                                   (if (empty? nbr-birds) 0 (/ (count nbr-birds) (get-param :initial-num-birds)))
                                   speed
                                   (:energy bird)]))

        ;; Compute outputs
        grn-outputs (grn/get-grn-outputs grn)
        grn-scale 10.0
        total-sum 1 #_(apply + grn-outputs)
        grn-outputs (map #(* (/ % total-sum) grn-scale) grn-outputs)
        closest-bird-weight (- (nth grn-outputs 0) (nth grn-outputs 1))
        closest-food-weight (- (nth grn-outputs 2) (nth grn-outputs 3))
        closest-random-weight (nth grn-outputs 4)
        velocity-weight (- (nth grn-outputs 5) (nth grn-outputs 6))
        centroid-weight (- (nth grn-outputs 7) (nth grn-outputs 8))

        new-acceleration (add (if closest-bird
                                (mul-vec3 dclosest-bird closest-bird-weight)
                                (vec3 0 0 0))
                              (if closest-food
                                (mul-vec3 dclosest-food closest-food-weight)
                                (vec3 0 0 0))
                              (if dclosest-bird
                                (mul-vec3 dcentroid centroid-weight)
                                (vec3 0 0 0))
                              (mul-vec3 (get-velocity bird) velocity-weight)
                              #_control-force
                              (mul-vec3 (lrand-vec3 -1 1 -1 1 -1 1) closest-random-weight))

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
                          (vec4 (/ (:energy bird) 10)
                                0 #_(/ (:num-proteins (:grn bird)) 50)
                                (/ (- (get-time) (:birth-time bird)) 500)
                                1.0))
               (bound-acceleration
                 new-acceleration))
        :newborn? false
        :grn grn
        :energy (- (:energy bird)
                   (* (get-dt) (:delta-proteins @params) (:num-proteins grn))
                   (* (get-dt) (:delta-bird-energy @params))
                   (* (get-dt) (:delta-movement @params) (length (get-velocity bird))))

        ; Statistics
        :d-nbr-bird (if closest-bird (length dclosest-bird) 0)
        :d-nbr-food (if closest-food (length dclosest-food) 0)
        :d-centroid (if (empty? nbr-birds) 0 (length dcentroid))
        :num-nbr-birds (count nbr-birds)
        :num-nbr-foods (count nbr-foods)
         ))))
(enable-kinematics-update :bird)                            ; This tells the simulator to move our objects
(add-update-handler :bird fly)                              ; This tells the simulator how to update these objects
(add-global-update-handler 10
                           (fn []
                             (doseq [uid @death-queue]
                               (.deleteObject ^brevis.Engine @*java-engine* uid))
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
   (keyword (str (name series-name) "-N")) (count samples)
   })

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
    (println :objs (count objs) :birds (count birds) :foods (count foods))
    (merge {:t (get-time)
            :wall-time (get-wall-time)
            :num-birds (count birds)
            :num-foods (count foods)
            :num-collisions @collision-events
            :num-feedings @feeding-events
            :num-dead-birds @dead-birds
            :num-dead-before-reproduction @dead-before-reproduction
            :sum-reproductions-before-death @sum-reproductions-before-death
            :num-birds-added @num-birds-added
            }
           (series-statistics :bird-ages bird-ages)
           (series-statistics :food-energies food-energies)
           (series-statistics :bird-energies bird-energies)
           (series-statistics :breed-count (map :breed-count birds))
           (series-statistics :d-nbr-bird (map :d-nbr-bird birds))
           (series-statistics :d-nbr-food (map :d-nbr-food birds))
           (series-statistics :d-centroid (map :d-centroid birds))
           (series-statistics :num-nbr-birds (map :num-nbr-birds birds))
           (series-statistics :num-nbr-foods (map :num-nbr-foods birds))
           (series-statistics :num-proteins (map #(:num-proteins (:grn %)) birds))
           )))

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
#_(add-global-update-handler 3
                             (fn []
                               (when (and (pos? (:screenshot-interval @params))
                                          (> (get-time) (* (:screenshot-interval @params) @screenshot-num)))
                                 (screenshot (str "feedbackSwarmEvolve_t_" (get-time) ".png"))
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
  [(assoc bird1 :energy (- (:energy bird1) (* (get-dt) (:delta-collision @params))))
   (assoc bird2 :energy (- (:energy bird2) (* (get-dt) (:delta-collision @params))))])
(add-collision-handler :bird :bird bump)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## brevis control code
(defn initialize-simulation
  "This is the function where you add everything to the world."
  []
  (init-world)
  (init-view)
  (grn/initialize-grneat)
  (swap! brevis.globals/*gui-state* assoc :gui (:gui @params))

  (.setPosition (:camera @brevis.globals/*gui-state*) (vec3 0.0 -385 0))
  (.setRotation (:camera @brevis.globals/*gui-state*) (vec4 90 0 -90 0))

  (set-dt (:dt @params))
  (set-neighborhood-radius 200)

  (dotimes [_ (:num-foods @params)]
    (add-object (random-food)))

  (dotimes [_ (:initial-num-birds @params)]
    (if (:initial-bird-grn @params)
      (add-object (load-bird (:initial-bird-grn-filename @params)))
      (add-object (random-bird))))

  (when (get-param :gui)

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
        :title "Age")
    )
  )

(defn -main [& args]
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

    (doseq [[k v] @params]
      (log-string (str k "\t" (if (string? v) (str "\"" v "\"") v) "\n")))
    (log-string "@DATA\n")
    (with-rng rng
              ((if (:gui @params) start-gui start-nogui)
                initialize-simulation java-update-world))))

;; For autostart with Counterclockwise in Eclipse
(when
  (find-ns 'ccw.complete)
  (-main))

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
                     :breed-energy-threshold 1.1                          ; threshold that triggers spawning
                     }]
    (for [food-radius [5 10 15]
          num-foods [15 25]
          food-migration-probability [0 0.01 0.001]
          ;food-migration-probability [0.001]
          ;selection-attribute [:age :energy :none]
          delta-collision [0 0.005 0.01 0.015 0.02] 
          selection-attribute [:age :none]
          delta-consumption [0.1]
          delta-bird-energy [0.005]
          ;delta-consumption [0.05 0.15]
          ;delta-bird-energy [0.005 0.01]
          ]
      [(merge base-params
             {:tag (str "params_fR=" food-radius "_nF=" num-foods "_fMP=" food-migration-probability "_dCollision=" delta-collision "_sA=" selection-attribute "_dConsumption=" delta-consumption "_dBE=" delta-bird-energy)
              :food-radius food-radius
              :num-foods num-foods
              :food-migration-probability food-migration-probability
              :delta-collision delta-collision
              :selection-attribute selection-attribute
              :delta-consumption delta-consumption
              :delta-bird-energy delta-bird-energy})])))

; Launching runs
(defn cluster-launch-multi
  []
  (let [experiment-name "ecal2017_ecoevo_swarm_combo_103"
        argmaps (get-argmaps-experiment-combo experiment-name)]
    (println "Launching " (count argmaps) "number of runs")
    (dotimes [k (count argmaps)]
      (let [argmap (nth argmaps k)
            experiment-name (str experiment-name "_" k)]
;        (println argmap)
        (distributed/start-runs
         (into [] (map #(assoc % :output-directory "PROBABLY THE SAME DIRECTORY AS YOUR CLUSTER STORAGE FOR THIS PROJECT")
                       argmap))
         "grneat-swarm-feedback-control.core"
         experiment-name
         "CLUSTER USERNAME"; assumes ssh-keys setup
         "CLUSTER HOSTNAME"
         25
         "FULL PATH TO YOUR LOCAL DIRECTORY CONTAINING THIS PROJECT"
         "PATH TO THE DIRECTORY TO STORE YOUR PROJECT ON CLUSTER"
         "EXTRA FLAGS FOR CLUSTER LAUNCH")))))


(-main)
