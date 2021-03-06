package com.typesafe.sbt.bundle

import com.typesafe.sbt.SbtNativePackager
import com.typesafe.sbt.packager.universal.Archives
import java.io.{ FileInputStream, BufferedInputStream }
import java.nio.charset.Charset
import java.security.MessageDigest
import sbt._
import sbt.Keys._
import SbtNativePackager.Universal

import scala.annotation.tailrec

object Import {

  case class Endpoint(protocol: String, bindPort: Int, services: Set[URI])

  object BundleKeys {

    // Scheduling settings

    val system = SettingKey[String](
      "bundle-system",
      "A logical name that can be used to associate multiple bundles with each other."
    )

    val nrOfCpus = SettingKey[Double](
      "bundle-nr-of-cpus",
      "The number of cpus required to run the bundle (can be fractions thereby expressing a portion of CPU). Required."
    )

    val memory = SettingKey[Bytes](
      "bundle-memory",
      "The amount of memory required to run the bundle. This value must a multiple of 1024 greater than 2 MB. Append the letter k or K to indicate kilobytes, or m or M to indicate megabytes. Required."
    )

    val diskSpace = SettingKey[Bytes](
      "bundle-disk-space",
      "The amount of disk space required to host an expanded bundle and configuration. Append the letter k or K to indicate kilobytes, or m or M to indicate megabytes. Required."
    )

    val roles = SettingKey[Set[String]](
      "bundle-roles",
      "The types of node in the cluster that this bundle can be deployed to. Defaults to having no specific roles."
    )

    // General settings

    val bundleConf = TaskKey[String](
      "bundle-conf",
      "The bundle configuration file contents"
    )

    val bundleType = SettingKey[Configuration](
      "bundle-type",
      "The type of configuration that this bundling relates to. By default Universal is used."
    )

    val startCommand = SettingKey[Seq[String]](
      "bundle-start-command",
      "Command line args required to start the component. Paths are expressed relative to the component's bin folder. The default is to use the bash script in the bin folder."
    )

    val endpoints = SettingKey[Map[String, Endpoint]](
      "bundle-endpoints",
      """Declares endpoints. The default is Map("web" -> Endpoint("http", 0, 9000, "$name")) where the service name is the name of this project. The "web" key is used to form a set of environment variables for your components. For example you will have a `WEB_BIND_PORT` in this example."""
    )
  }

  case class Bytes(underlying: Long) extends AnyVal {
    def round1k: Bytes =
      Bytes((Math.max(underlying - 1, 0) >> 10 << 10) + 1024)
  }

  object ByteConversions {
    implicit class IntOps(value: Int) {
      def KB: Bytes =
        Bytes(value * 1000L)
      def MB: Bytes =
        Bytes(value * 1000000L)
      def GB: Bytes =
        Bytes(value * 1000000000L)
      def TB: Bytes =
        Bytes(value * 1000000000000L)
      def KiB: Bytes =
        Bytes(value.toLong << 10)
      def MiB: Bytes =
        Bytes(value.toLong << 20)
      def GiB: Bytes =
        Bytes(value.toLong << 30)
      def TiB: Bytes =
        Bytes(value.toLong << 40)
    }
  }

  object URI {
    def apply(uri: String): URI =
      new sbt.URI(uri)
  }

  val Bundle = config("bundle") extend Universal
}

object SbtBundle extends AutoPlugin {

  import Import._
  import BundleKeys._
  import SbtNativePackager.autoImport._

  val autoImport = Import

  private final val Sha256 = "SHA-256"

  private val utf8 = Charset.forName("utf-8")

  override def `requires` = SbtNativePackager

  override def trigger = AllRequirements

  override def projectSettings = Seq(
    system := (packageName in Universal).value,
    roles := Set.empty,

    bundleConf := getConfig.value,
    bundleType := Universal,
    startCommand := Seq(
      (file((packageName in Universal).value) / "bin" / (executableScriptName in Universal).value).getPath,
      s"-J-Xms${memory.value.round1k.underlying}",
      s"-J-Xmx${memory.value.round1k.underlying}"
    ),
    endpoints := Map("web" -> Endpoint("http", 0, Set(URI(s"http://:9000")))),
    NativePackagerKeys.dist in Bundle := Def.taskDyn {
      Def.task {
        createDist(bundleType.value)
      }.value
    }.value,
    NativePackagerKeys.stage in Bundle := Def.taskDyn {
      Def.task {
        stageBundle(bundleType.value)
      }.value
    }.value,
    NativePackagerKeys.stagingDirectory in Bundle := (target in Bundle).value / "stage",
    target in Bundle := target.value / "typesafe-conductr"
  )

  private def createDist(bundleTypeConfig: Configuration): Def.Initialize[Task[File]] = Def.task {
    val bundleTarget = (target in Bundle).value
    val configTarget = bundleTarget / "tmp"
    def relParent(p: (File, String)): (File, String) =
      (p._1, (packageName in Universal).value + java.io.File.separator + p._2)
    val configFile = writeConfig(configTarget, bundleConf.value)
    val bundleMappings =
      configFile.pair(relativeTo(configTarget)) ++ (mappings in bundleTypeConfig).value.map(relParent)
    val name = (packageName in Universal).value
    val archive = Archives.makeZip(bundleTarget, name, bundleMappings, Some(name))
    val archiveName = archive.getName
    val exti = archiveName.lastIndexOf('.')
    val hash = Hash.toHex(digestFile(archive))
    val hashName = archiveName.take(exti) + "-" + hash + archiveName.drop(exti)
    val hashArchive = archive.getParentFile / hashName
    IO.move(archive, hashArchive)
    hashArchive
  }

  private def digestFile(f: File): Array[Byte] = {
    val digest = MessageDigest.getInstance(Sha256)
    val in = new BufferedInputStream(new FileInputStream(f))
    val buf = Array.ofDim[Byte](8192)
    try {
      @tailrec
      def readAndUpdate(r: Int): Unit =
        if (r != -1) {
          digest.update(buf, 0, r)
          readAndUpdate(in.read(buf))
        }
      readAndUpdate(in.read(buf))
      digest.digest
    } finally {
      in.close()
    }
  }

  private def formatSeq(strings: Iterable[String]): String =
    strings.map(s => s""""$s"""").mkString("[", ", ", "]")

  private def formatEndpoints(endpoints: Map[String, Endpoint]): String = {
    val formatted =
      for {
        (label, Endpoint(protocol, bindPort, services)) <- endpoints
      } yield s"""|      "$label" = {
                  |        protocol  = "$protocol"
                  |        bind-port = $bindPort
                  |        services  = [${services.mkString("\"", "\", \"", "\"")}]
                  |      }""".stripMargin
    formatted.mkString(f"{%n", f",%n", f"%n    }")
  }

  private def getConfig: Def.Initialize[Task[String]] = Def.task {
    s"""|version    = "1.0.0"
        |name       = "${name.value}"
        |system     = "${system.value}"
        |nrOfCpus   = ${nrOfCpus.value}
        |memory     = ${memory.value.underlying}
        |diskSpace  = ${diskSpace.value.underlying}
        |roles      = ${formatSeq(roles.value)}
        |components = {
        |  "${(packageName in Universal).value}" = {
        |    description      = "${projectInfo.value.description}"
        |    file-system-type = "${bundleType.value}"
        |    start-command    = ${formatSeq(startCommand.value)}
        |    endpoints        = ${formatEndpoints(endpoints.value)}
        |  }
        |}
        |""".stripMargin
  }

  private def stageBundle(bundleTypeConfig: Configuration): Def.Initialize[Task[File]] = Def.task {
    val bundleTarget = (NativePackagerKeys.stagingDirectory in Bundle).value
    writeConfig(bundleTarget, bundleConf.value)
    val componentTarget = bundleTarget / (packageName in Universal).value
    IO.copy((mappings in bundleTypeConfig).value.map(p => (p._1, componentTarget / p._2)))
    componentTarget
  }

  private def writeConfig(target: File, contents: String): File = {
    val configFile = target / "bundle.conf"
    IO.write(configFile, contents, utf8)
    configFile
  }
}
