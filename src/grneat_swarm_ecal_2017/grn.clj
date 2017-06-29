(ns grneat-swarm-ecal-2017.grn
  (:require [clj-random.core :as random])
  (:use [brevis.physics collision core space utils]        
        [brevis core osd vector plot random parameters])
  (:import [evolver GRNGenome GRNGene]
           [evaluators GRNGenomeEvaluator]
           [grn GRNProtein GRNModel]
           [java.util Random]
           [operators GRNAddGeneMutationOperator
            GRNAligningCrossoverOperator_ParentCountProb
            GRNAligningCrossoverOperator_v1
            GRNAligningCrossoverOperator_v1b
            GRNAligningCrossoverOperator_v2
            GRNCrossoverOperator
            GRNDeleteGeneMutationOperator
            GRNGeneMutationOperator
            GRNMutationOperator
            GRNOnePointCrossoverOperator]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ## Globals

(swap! params assoc
       :num-GRN-inputs 2
       :num-GRN-outputs 2
       :num-GRN-steps 1
       :grn-mutation-add-max-size Integer/MAX_VALUE
       :grn-mutation-add-probability 0.33
       :grn-mutation-del-min-size 0
       :grn-mutation-del-probability 0.33
       :grn-mutation-change-probability 0.33
       )

(defn initialize-grneat
  "Initiatialize globals, such as evolutionary features."
  []
  (let [mutators {(GRNAddGeneMutationOperator. (get-param :grn-mutation-add-max-size) (get-param :grn-mutation-add-probability)) (get-param :grn-mutation-add-probability),
                  (GRNDeleteGeneMutationOperator. ^int (max (get-param :grn-mutation-del-min-size) (+ (get-param :num-GRN-inputs) (get-param :num-GRN-outputs) 1) (get-param :grn-mutation-del-probability))) (get-param :grn-mutation-del-probability),
                  (GRNGeneMutationOperator. (get-param :grn-mutation-change-probability)) (get-param :grn-mutation-change-probability)}
        crossovers {;(GRNAligningCrossoverOperator_v1.)
                    ;(GRNAligningCrossoverOperator_v1b.)
                    ;(GRNAligningCrossoverOperator_v2.)
                    ;(GRNOnePointCrossoverOperator.)
                    (GRNAligningCrossoverOperator_ParentCountProb.) nil}] 
    (set-param :grn-rng (Random.))
    (set-param :grn-mutators mutators)
    (set-param :grn-crossovers crossovers)))

(defn make-genome
  "Make a genome."
  []
  (let [^GRNGenome genome (GRNGenome.)
        beta-max (.getBetaMax genome)
        beta-min (.getBetaMin genome)
        delta-max (.getDeltaMax genome)
        delta-min (.getDeltaMin genome)]
    (dotimes [k (get-param :num-GRN-inputs)]
      (.addGene genome 
        (GRNGene/generateRandomGene GRNProtein/INPUT_PROTEIN k (get-param :grn-rng) #_random/*RNG*)))
    (dotimes [k (get-param :num-GRN-outputs)]
      (.addGene genome
        (GRNGene/generateRandomGene GRNProtein/OUTPUT_PROTEIN k (get-param :grn-rng) #_random/*RNG*)))
    ; Could to great init here (small genomes)
    (dotimes [k (inc (lrand-int (- 50 (get-param :num-GRN-inputs) (get-param :num-GRN-outputs))))]
      (.addGene genome 
        (GRNGene/generateRandomRegulatoryGene (get-param :grn-rng) #_random/*RNG*)))
    (.setBeta genome (+ (* (- beta-max beta-min) (lrand)) beta-min))                        
    (.setDelta genome (+ (* (- delta-max delta-min) (lrand)) delta-min))
    genome))                        

(defn make-grn-state
  "Make the state of a GRN."
  [^GRNGenome genome]
  ;(println (.toString genome))
  (GRNGenomeEvaluator/buildGRNFromGenome genome))

(defn make-grn
  "Return a GRN, with a genome and a state."
  []
  (let [^GRNGenome genome (make-genome)
        grn-state (make-grn-state genome)]
    {:state grn-state
     :num-proteins (.size (.proteins ^GRNModel grn-state))
     :genome genome}))

(defn load-from-file
  [filename]
  (let [grn-state (grn.GRNModel/loadFromFile filename)]
    {:state grn-state
     :num-proteins (.size (.proteins ^GRNModel grn-state))
     :genome (make-genome)}))


(defn reset-grn
  "Reset a GRN state to initial values."
  [grn]
  (.reset ^GRNModel (:state grn))
  grn)

(defn set-grn-inputs
  "Set the GRN inputs."
  [grn inputs]
  (let [proteins (.proteins ^GRNModel (:state grn))]
    (doall (map #(.setConcentration ^GRNProtein (.get proteins %1) %2)
                (range) inputs))
    grn))

(defn update-grn
  "Update the state of a GRN."
  [grn]
  (.evolve ^GRNModel (:state grn)
    ^int (get-param :num-GRN-steps))
  #_(println "-----------------------------")
  #_(println grn-state)
  grn)

(defn get-grn-outputs
  "Get the GRN outputs."
  [grn]
  (let [proteins (.proteins ^GRNModel (:state grn))]
    (for [oid (range (get-param :num-GRN-inputs)
                     (+ (get-param :num-GRN-inputs) (get-param :num-GRN-outputs)))]
      (.getConcentration ^GRNProtein (.get proteins oid)))))

(defn select-mutation-operator
  "Select a mutation operator."
  []
  (let [rnd (lrand-nth (keys (get-param :grn-mutators)))]; we know uniform for now, laziness
    rnd))

(defn mutate
  "Mutate a GRN. Resets the grn-state."
  [grn]
  (let [mutant-genome (loop []
                        (let [^GRNMutationOperator mutator (select-mutation-operator)
                              mutant-genome (.cloneAndMutate mutator (:genome grn) (get-param :grn-rng) #_random/*RNG*)]
                          (if mutant-genome
                            mutant-genome
                            (recur))))
        grn-state (make-grn-state mutant-genome)]
    (.reset ^GRNModel grn-state)
    {:state grn-state
     :num-proteins (.size (.proteins ^GRNModel grn-state)) 
     :genome mutant-genome}))

(defn select-crossover-operator
  "Select a crossover operator."
  []
  (let [rnd (lrand-nth (keys (get-param :grn-crossovers)))]; we know uniform for now, laziness
    rnd))

(defn crossover
  "Mutate a GRN. Resets the grn-state."
  [p1 p2]
  (let [mutant-genome (loop []
                        (let [^GRNCrossoverOperator crossoveror (select-crossover-operator)
                              mutant-genome (.reproduce crossoveror
                                                 (:genome p1)
                                                 (:genome p2)
                                                 (get-param :grn-rng) #_random/*RNG*)]
                          (if mutant-genome
                            mutant-genome
                            (recur))))
        grn-state (make-grn-state mutant-genome)]
    (.reset ^GRNModel grn-state)
    {:state grn-state
     :num-proteins (.size (.proteins ^GRNModel grn-state)) 
     :genome mutant-genome}))
