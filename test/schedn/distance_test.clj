(ns shedn.distance-test
  (:use clojure.test
        schedn.distance))

(deftest levenshtein-distance-tests
  (testing "Levenshtein distance metric on the wikipedia examples"
    (is (= 6 (levenshtein-distance "toned" "roses")))
    (is (= 2 (levenshtein-distance "it" "is")))
    (is (= 1 (levenshtein-distance "it" "is" 1)))
    (is (= 5 (levenshtein-distance "kitten" "sitting")))
    (is (= 3 (levenshtein-distance "kitten" "sitting" 1))) ;;wikipedia actully uses 1 for substitution
    (is (= 26 (levenshtein-distance "abcdefghijklmnopqrstuvwxyz" "")))
    (let [alphabet "abcdefghijklmnopqrstuvwxyz"
          ralphabet (->> alphabet reverse (apply str))]
      (is (= 50 (levenshtein-distance alphabet ralphabet))))))


(deftest fuzzy-match-tests
  (testing "String fuzzy-match based on edit-distance"
    (let [s "physics"
          candidates ["physiscian" "physical" "physio" "physically" "physicalness" "NO_MATCH"]]
      (is (= ["physical" "physio"] (fuzzy-match s candidates 3)))
      (is (= ["physical" "physio"] (fuzzy-match s candidates 4)))
      (is (= ["physiscian" "physical" "physio" "physically" "physicalness"] (fuzzy-match s candidates 5))))))

