import sbt.contraband.ast._
import sbt.contraband.CodecCodeGen

object ContrabandConfig {

  /** Extract the only type parameter from a TpeRef */
  def oneArg(tpe: Type): Type = {
    val pat = s"""${tpe.removeTypeParameters.name}[<\\[](.+?)[>\\]]""".r
    val pat(arg0) = tpe.name
    NamedType(arg0 split '.' toList)
  }

  /** Extract the two type parameters from a TpeRef */
  def twoArgs(tpe: Type): List[Type] = {
    val pat = s"""${tpe.removeTypeParameters.name}[<\\[](.+?), (.+?)[>\\]]""".r
    val pat(arg0, arg1) = tpe.name
    NamedType(arg0 split '.' toList) :: NamedType(arg1 split '.' toList) :: Nil
  }

  /** sbt codecs */
  val sbtCodecs: PartialFunction[String, Type => List[String]] = {
    // sbt-contraband should handle them by default
    case "Option" | "Set" | "scala.Vector" => { tpe => getFormats(oneArg(tpe)) }
    case "Map" | "Tuple2" | "scala.Tuple2" => { tpe =>
      twoArgs(tpe).flatMap(getFormats)
    }
    case "Int" | "Long" => { _ => Nil }
    case "scalajson.ast.unsafe.JValue" |
        "sjsonnew.shaded.scalajson.ast.unsafe.JValue" => { _ =>
      List("sbt.internal.util.codec.JValueFormats")
    }

    case "sbt.internal.bsp.BuildTargetIdentifier" => { _ =>
      List("sbt.internal.bsp.codec.BuildTargetIdentifierFormats")
    }
  }

  /** Returns the list of formats required to encode the given `TpeRef`. */
  val getFormats: Type => List[String] =
    CodecCodeGen.extensibleFormatsForType {
      case tpe: Type if sbtCodecs.isDefinedAt(tpe.removeTypeParameters.name) =>
        sbtCodecs(tpe.removeTypeParameters.name)(tpe)
      case other => CodecCodeGen.formatsForType(other)
    }
}
