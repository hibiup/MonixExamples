name := "MonixExamples"
version := "0.1"
scalaVersion := "2.13.1"

// DEPENDENCIES
lazy val testing = Seq(
        "org.scalatest" %% "scalatest" % "3.0.8",
        "com.storm-enroute" %% "scalameter" % "0.19"
)

lazy val logging ={
        val logbackV = "1.2.3"
        val scalaLoggingV = "3.9.2"
        Seq(
                "ch.qos.logback" % "logback-classic" % logbackV,
                "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV
        )
}

lazy val monix = Seq(
        "io.monix" %% "monix" % "3.0.0"
)

lazy val MonixExamples = project.in(file(".")).settings(
        name:="MonixExamples",
        libraryDependencies ++= testing ++ logging ++ monix
)
