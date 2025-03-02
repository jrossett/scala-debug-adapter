package ch.epfl.scala.debugadapter.internal

import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util
import scala.util.control.NonFatal

private[debugadapter] object IO {
  def withinJarFile[T](absolutePath: Path)(f: FileSystem => T): Option[T] = {
    val uri = URI.create(s"jar:${absolutePath.toUri}")
    val fileSystem =
      FileSystems.newFileSystem(uri, new util.HashMap[String, Any])
    try Some(f(fileSystem))
    catch {
      case NonFatal(e) => None
    } finally fileSystem.close()
  }

  def withinJavaRuntimeFileSystem[T](classLoader: ClassLoader, javaHome: Path)(
      f: FileSystem => T
  ): T = {
    val properties =
      util.Collections.singletonMap("java.home", javaHome.toString)
    // In case of memory leak, see: https://stackoverflow.com/questions/68083239/how-to-free-all-resources-after-reading-a-jrt
    val fileSystem =
      FileSystems.newFileSystem(URI.create("jrt:/"), properties, classLoader)
    try f(fileSystem)
    finally fileSystem.close()
  }

}
