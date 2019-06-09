(defproject grneat-swarm-ecal-2017 "0.1.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ^:replace ["-Djava.rmi.server.hostname=localhost" "-Dscenery.Renderer=OpenGLRenderer"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [us.brevis/brevis "de51c17"]
                 [us.brevis/fun.grn "9c3f5d4"]
                 [brevis.us/brevis-utils "0.1.2"]]
  :repositories [["scijava.public" "https://maven.scijava.org/content/groups/public"]
                 ["brevis-bintray" "https://dl.bintray.com/kephale/brevis"]
                 ["jitpack.io" "https://jitpack.io"]])
