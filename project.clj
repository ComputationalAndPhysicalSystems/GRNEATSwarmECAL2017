(defproject grneat-swarm-ecal-2017 "0.1.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ^:replace ["-Djava.rmi.server.hostname=localhost"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 ;[brevis "0.10.3"]
                 [com.github.kephale/brevis "0cd1280d918c59f263ea0894e514b88169985e2a"]
                 [us.brevis/GRNEAT "0.0.3"]]
  :repositories [["imagej" "http://maven.imagej.net/content/groups/hosted/"]
                 ["imagej-releases" "http://maven.imagej.net/content/repositories/releases/"]
                 ["ome maven" "http://artifacts.openmicroscopy.org/artifactory/maven/"]
                 ["imagej-snapshots" "http://maven.imagej.net/content/repositories/snapshots/"]
                 ["brevis-bintray" "https://dl.bintray.com/kephale/brevis"]
                 ["jitpack.io" "https://jitpack.io"]
                 ["clojars2" {:url "http://clojars.org/repo/"
                              :username :env/LEIN_USERNAME
                              :password :env/LEIN_PASSWORD}]])
