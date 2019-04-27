(defproject grneat-swarm-ecal-2017 "0.1.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ^:replace ["-Djava.rmi.server.hostname=localhost" "-Dscenery.Renderer=OpenGLRenderer"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 ;[brevis "0.10.3"]
                 [us.brevis/brevis "5848388b0d"]
                 [us.brevis/grneat "2e68743ff7"]]
  :repositories [["imagej" "https://maven.imagej.net/content/groups/hosted/"]
                 ["imagej-releases" "https://maven.imagej.net/content/repositories/releases/"]
                 ["ome maven" "https://artifacts.openmicroscopy.org/artifactory/maven/"]
                 ["imagej-snapshots" "https://maven.imagej.net/content/repositories/snapshots/"]
                 ["brevis-bintray" "https://dl.bintray.com/kephale/brevis"]
                 ["jitpack.io" "https://jitpack.io"]])
