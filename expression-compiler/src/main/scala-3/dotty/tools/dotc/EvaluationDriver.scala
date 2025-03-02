package dotty.tools.dotc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.util.SourceFile

class EvaluationDriver(
    settings: List[String],
    expressionClassName: String,
    breakpointLine: Int,
    expression: String,
    defNames: Set[String]
) extends Driver:
  override def sourcesRequired: Boolean = false

  private val myInitCtx: Context =
    val rootCtx = initCtx.fresh
    val ctx = setup(settings.toArray, rootCtx) match
      case Some((_, ctx)) =>
        ctx
      case None => rootCtx
    ctx.initialize()(using ctx)
    ctx

  def run(sourceCode: String): List[Diagnostic] =
    val sourceFile = SourceFile.virtual("<source>", sourceCode)
    val reporter = new StoreReporter()
    val ctx = myInitCtx.fresh.setReporter(reporter)
    val compiler: Compiler =
      EvaluationCompiler(
        sourceFile,
        expressionClassName,
        breakpointLine,
        expression,
        defNames
      )(using ctx)
    try
      val run = compiler.newRun(using ctx)
      run.compileSources(List(sourceFile))
      // reporter.pendingMessages(using ctx).foreach(d => println(d.msg))
      reporter.allErrors
    catch
      case e =>
        // reporter.pendingMessages(using ctx).foreach(d => println(d.msg))
        e.printStackTrace()
        throw e
