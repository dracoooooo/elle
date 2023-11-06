(ns elle.graph-test
  (:require [bifurcan-clj [core :as b]
                          [graph :as bg]
                          [set :as bs]]
            [clojure [pprint :refer [pprint]]]
            [elle.graph :refer :all]
            [jepsen.txn :as txn]
            [clojure.test :refer :all]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (io.lacuna.bifurcan IMap
                               Map)
           (elle RelGraph)))

(deftest tarjan-test
  (let [tarjan (comp set tarjan)]
    (testing "Can analyze integer graphs"
      ;; From wikipedia
      (let [graph {1 #{2}   2 #{3}
                   3 #{1}   4 #{2 3 5}
                   5 #{4 6} 6 #{3 7}
                   7 #{6}   8 #{7 8}}]
        (is (= (tarjan graph)
               #{#{3 2 1} #{6 7} #{5 4}})))

      ;; Big lööp
      (let [graph {1 #{2} 2 #{3}
                   3 #{4} 4 #{5}
                   5 #{6} 6 #{7}
                   7 #{8} 8 #{1}}]
        (is (= (tarjan graph)
               #{#{1 2 3 4 5 6 7 8}})))

      ;; smol lööps
      (let [graph {0 #{1} 1 #{0}
                   2 #{3} 3 #{2}
                   4 #{5} 5 #{4}
                   6 #{7} 7 #{6}}]
        (is (= (tarjan graph)
               #{#{0 1} #{2 3}
                 #{4 5} #{6 7}}))))

    (testing "Can flag unlinked as solo sccs"
      (let [graph {1 #{} 2 #{}
                   3 #{} 4 #{}}]
        (is (= (tarjan graph)
               #{}))))

    (testing "Can flag self-ref as solo sccs"
      (let [graph {1 #{1} 2 #{2}
                   3 #{3} 4 #{4}}]
        (is (= (tarjan graph)
               #{}))))

    (testing "can check monotonic loop histories"
      ;; Linear
      (let [graph {0 #{1} 1 #{2}
                   2 #{3} 3 #{}}]
        (is (= (tarjan graph)
               #{})))

      ;; Loop
      (let [graph {0 #{1} 1 #{2}
                   2 #{1} 3 #{}}]
        (is (= (tarjan graph)
               #{#{1 2}})))

      ;; Linear but previously bugged case
      (let [graph {0 #{1} 1 #{2}
                   2 #{}  3 #{2 1}}]
        (is (= (tarjan graph)
               #{})))

      (let [graph {0 #{1} 1 #{0}
                   2 #{}  3 #{2 1}}]
        (is (= (tarjan graph)
               #{#{0 1}})))

      ;; FIXME Busted case
      (let [graph {1 #{7 3 5} 3 #{7 5}
                   5 #{}      7 #{3 5}}]
        (is (= (tarjan graph)
               #{#{3 7}}))))

    (testing "can check a one node graph"
      (let [graph {0 #{}}]
        (is (= (tarjan graph)
               #{}))))

    (testing "busted"
      (let [graph {1 #{7 3 5} 3 #{7 5}
                   5 #{}      7 #{3 5}}]
        (is (= (tarjan graph)
               #{#{3 7}}))))

    (testing "wiki"
      (let [graph {1 #{2}   2 #{3}
                   3 #{1}   4 #{2 3 5}
                   5 #{4 6} 6 #{3 7}
                   7 #{6}   8 #{7 8}}]
        (is (= (tarjan graph)
               #{#{3 2 1} #{6 7} #{5 4}}))))))

(deftest path-shells-test
  (let [g     (map->bdigraph {0 [1 2] 1 [3] 2 [3] 3 [0]})
        paths (path-shells g [[0]])]
    (is (= [[[0]]
            [[0 1] [0 2]]
            [[0 1 3]]
            [[0 1 3 0]]]
           (take 4 paths)))))

(deftest find-cycle-test
  (let [g (map->bdigraph {0 [1 2]
                          1 [4]
                          2 [3]
                          3 [4]
                          4 [0 2]})]
    (testing "basic cycle"
      (is (= [0 1 4 0]
             (find-cycle g))))

    ; We may restrict a graph to a particular relationship and look for cycles
    ; in an SCC found in a larger graph; this should still work.
    (testing "scc without cycle in graph"
      (is (= nil
             (find-cycle (bg/select g (bs/from #{0 2 4}))))))

    (testing "cycle in restricted scc"
      (is (= [0 1 4 0]
             (find-cycle g))))))

(deftest find-cycle-starting-with-test
  (let [initial   (map->bdigraph {0 [1 2]})
        ; Remaining HAS a cycle, but we don't want to find it.
        remaining (map->bdigraph {1 [3]
                                  3 [1 0]})]
    (is (= [0 1 3 0]
           (find-cycle-starting-with initial remaining)))))

(deftest fallback-cycle-test
  (is (= [2 3 4 2] (fallback-cycle
                     (map->bdigraph {1 [2]
                                     2 [3]
                                     3 [4]
                                     4 [2]})))))

(deftest find-cycle-satisfying-test
  ; This transition function considers every path legal.
  (let [trivial (fn trivial
                  ([v] :trivial)
                  ([state path rel v'] state))
        ; This fn ensures that no :rw is next to another by testing successive
        ; edge types. In addition, we ensure that the first edge in the cycle
        ; is not an rw. Cycles must have at least two edges, and in order for
        ; no two rw edges to be adjacent, there must be at least one non-rw
        ; edge among them. This constraint ensures a sort of boundary condition
        ; for the first and last nodes--even if the last edge is rw, we don't
        ; have to worry about violating the nonadjacency property when we jump
        ; to the first.
        nonadjacent (fn
                      ([v] true) ; To start, pretend we just came along an rw
                      ([last-was-rw? path rel v']
                       ; It's fine to follow *non* rw links, but if you've only
                       ; got rw, and we just did one, this path is invalid.
                       (let [rw? (= (bset :rw) rel)]
                         (if (and last-was-rw? rw?)
                           :elle.graph/invalid
                           rw?))))

        ; This predicate is always true.
        always (fn [_] true)]

    (testing "empty graph"
      (is (= nil (find-cycle-with- trivial always
                                   (map->bdigraph {})))))

    (testing "singleton scc"
      (is (= (->PathState [1 1] [nil] :trivial)
             (find-cycle-with- trivial
                                   always
                                   (map->bdigraph {1 [1]})))))

    (testing "basic cycle"
      (is (= (->PathState [2 3 2] [nil nil] :trivial)
             (find-cycle-with- trivial
                               always
                               (map->bdigraph {1 [2]
                                               2 [3]
                                               3 [2]})))))

    (testing "non-adjacent"
      (testing "double rw"
        (is (= nil (find-cycle-with nonadjacent
                                    always
                                    (-> (digraph)
                                        (link 1 2 :rw)
                                        (link 2 1 :rw))))))
      (testing "rw, rw+ww"
        (is (= [2 1 2] (find-cycle-with nonadjacent
                                        always
                                        (-> (digraph)
                                            (link 1 2 :rw)
                                            (link 2 1 :rw)
                                            (link 2 1 :ww))))))

      (testing "rw, ww, rw"
        (is (= nil (find-cycle-with nonadjacent
                                    always
                                    (-> (digraph)
                                        (link 1 2 :rw)
                                        (link 2 3 :ww)
                                        (link 3 1 :rw))))))

      (testing "rw, ww, rw, ww"
        (is (= [2 3 4 1 2] (find-cycle-with nonadjacent
                                            always
                                            (-> (digraph)
                                                (link 1 2 :rw)
                                                (link 2 3 :ww)
                                                (link 3 4 :rw)
                                                (link 4 1 :ww)))))))))

(deftest renumber-graph-test
  (is (= [{} []]
         (update (renumber-graph (map->bdigraph {})) 0 ->clj)))
  (is (= [{0 #{1 3}
           1 #{0}
           2 #{0}
           3 #{}}
          [:y :t :x :z]]
         (update (renumber-graph (map->bdigraph {:x #{:y}
                                                 :y #{:z :t}
                                                 :z #{}
                                                 :t #{:y}}))
                 0 ->clj))))

(deftest link-test
  (let [g (-> (digraph)
              (link 1 2 :foo)
              (link 1 2 :bar))]
    (is (= #{:foo :bar} (->clj (edge g 1 2))))))

(deftest collapse-graph-test
  (testing "simple"
    (is (= (map->bdigraph {1 [3]})
           (->> (map->bdigraph {1 [2]
                                2 [3]})
                (collapse-graph odd?)))))

  (testing "complex"
    (is (= (map->bdigraph {1 [1 5 7]
                           3 [1 5 7 9]})
           (->> (map->bdigraph {1 [4]
                                3 [4 9]
                                4 [5 6 7]
                                6 [1]})
                (collapse-graph odd?))))))

(deftest map-vertices-test
  (testing "empty"
    (is (= (map->bdigraph {}) (map-vertices identity (map->bdigraph {})))))

  (testing "complex"
    (is (= (-> (linear (digraph))
               (link 1 1 :a)
               (link 1 2 :b)
               (link 1 2 :c))
           (map-vertices {1 1, 2 1, 3 2, 4 2}
                         (-> (linear (digraph))
                             (link 1 2 :a) ; becomes a self-edge
                             (link 1 3 :b) ; becomes 1->2
                             (link 2 4 :c) ; becomes 1->2
                             ))))))

(deftest rel-graph-test
  (let [a (.. (named-graph :a)
              (link 1 2)
              (link 1 3))
        b (.. (named-graph :b)
              (link 1 2)
              (link 1 4)
              (link 5 6))
        g (reduce rel-graph-union (rel-graph-union) [a b])]
    (is (= true (.isDirected g)))
    (is (= #{1 2 3 4 5 6} (->clj (.vertices g))))
    (is (= a (.projectRel g :a)))
    (is (= b (.projectRel g :b)))
    (is (= g (.projectRels g [:a :b])))
    (is (= #{2 3 4} (->clj (.out g 1))))
    (is (thrown? IllegalArgumentException (->clj (.out g 0))))
    (is (= #{6} (->clj (.out g 5))))))

(deftest sequential-composition-test
  (let [a (-> (bg/digraph)
              (bg/link :x1 :y1)
              (bg/link :x1 :y2)
              (bg/link :r  :s))
        b (-> (bg/digraph)
              (bg/link :y1 :z1)
              (bg/link :y1 :z2)
              (bg/link :y2 :z3)
              (bg/link :r  :q)
              (bg/link :q  :r))]
    (is (= (-> (bg/digraph)
               (bg/link :x1 :z1)
               (bg/link :x1 :z2)
               (bg/link :x1 :z3))
           (sequential-composition a b)))))
