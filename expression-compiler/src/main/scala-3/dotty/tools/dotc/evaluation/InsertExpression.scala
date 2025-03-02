package dotty.tools.dotc.evaluation

import dotty.tools.dotc.EvaluationContext
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd._
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Contexts.ctx
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.transform.MegaPhase.MiniPhase
import dotty.tools.dotc.util.SourceFile

/**
 * This phase:
 * - inserts the expression that is being evaluated in the line of the breakpoint
 * - inserts `Expression` class in a proper package
 */
class InsertExpression(using
    evalCtx: EvaluationContext
) extends Phase:
  override def phaseName: String = InsertExpression.name
  override def isCheckable: Boolean = false

  private val expressionClassSource =
    s"""class ${evalCtx.expressionClassName}(names: Array[String], values: Array[Object]):
       |  val valuesByName = names.zip(values).toMap
       |
       |  def evaluate(): Any =
       |    ()
       |
       |  def callPrivateMethod(obj: Any, methodName: String, paramTypeNames: Array[String], args: Array[Object]): Any =
       |    val methods = obj.getClass.getDeclaredMethods
       |    val method = methods
       |      .find { m => 
       |        m.getName == methodName && m.getParameterTypes.map(_.getName).toSeq == paramTypeNames.toSeq
       |      }
       |      .get
       |    method.setAccessible(true)
       |    method.invoke(obj, args: _*)
       |
       |  def getPrivateField(obj: Any, name: String): Any =
       |    val field = obj.getClass.getDeclaredField(name)
       |    field.setAccessible(true)
       |    field.get(obj)
       |
       |  def getStaticObject(className: String): Any =
       |    val clazz = getClass.getClassLoader.loadClass(className)
       |    val field = clazz.getDeclaredField("MODULE$$")
       |    field.setAccessible(true)
       |    field.get(null)
       |""".stripMargin

  override def run(using Context): Unit =
    val parsedExpression = parseExpression(evalCtx.expression)
    val parsedExpressionClass =
      parseExpressionClass(
        expressionClassSource
      )
    val tree = ctx.compilationUnit.untpdTree
    ctx.compilationUnit.untpdTree =
      TreeInserter(parsedExpression, parsedExpressionClass)
        .transform(tree)

  class TreeInserter(expression: Tree, expressionClass: Seq[Tree])
      extends UntypedTreeMap:
    private var expressionInserted = false

    override def transform(tree: Tree)(using Context): Tree =
      tree match
        case tree: PackageDef =>
          val transformed = super.transform(tree).asInstanceOf[PackageDef]
          if (expressionInserted)
            // set to `false` to prevent inserting `Expression` class in other `PackageDef`s
            expressionInserted = false
            cpy.PackageDef(transformed)(
              transformed.pid,
              transformed.stats ++ expressionClass.map(
                _.withSpan(tree.span)
              )
            )
          else transformed
        case tree: Template if isOnBreakpoint(tree) =>
          expressionInserted = true
          val exprBlock = mkExprBlock(expression, Literal(Constant(())))
          val newTemplate = cpy.Template(tree)(body = tree.body :+ exprBlock)
          super.transform(newTemplate)
        case tree @ DefDef(name, paramss, tpt, _) if isOnBreakpoint(tree) =>
          expressionInserted = true
          cpy.DefDef(tree)(
            name,
            paramss,
            tpt,
            mkExprBlock(expression, tree.rhs)
          )
        case tree @ ValDef(name, tpt, _) if isOnBreakpoint(tree) =>
          expressionInserted = true
          cpy.ValDef(tree)(name, tpt, mkExprBlock(expression, tree.rhs))
        case tree if isOnBreakpoint(tree) =>
          expressionInserted = true
          val expr = mkExprBlock(expression, tree)
            .asInstanceOf[Block]
          expr
        case tree =>
          super.transform(tree)

  private def parseExpression(expression: String)(using Context): Tree =
    val expressionSource =
      s"""object Expression:
         |  { $expression }
         |""".stripMargin
    val parsedExpression = parseSource("<wrapped-expression>", expressionSource)
    parsedExpression
      .asInstanceOf[PackageDef]
      .stats
      .head
      .asInstanceOf[ModuleDef]
      .impl
      .body
      .head

  private def parseExpressionClass(
      expressionClassSource: String
  )(using Context): Seq[Tree] =
    val parsedExpressionClass =
      parseSource("source", expressionClassSource)
        .asInstanceOf[PackageDef]
    parsedExpressionClass.stats

  private def parseSource(name: String, source: String)(using Context): Tree =
    val parser = Parsers.Parser(SourceFile.virtual(name, source))
    parser.parse()

  private def isOnBreakpoint(tree: untpd.Tree)(using Context): Boolean =
    val startLine =
      if tree.span.exists then tree.sourcePos.startLine + 1 else -1
    startLine == evalCtx.breakpointLine

  private def mkExprBlock(expr: Tree, tree: Tree)(using
      Context
  ): Tree =
    val ident = Ident(evalCtx.expressionTermName)
    val valDef = ValDef(evalCtx.expressionTermName, TypeTree(), expr)
    Block(List(valDef, ident), tree)
end InsertExpression

object InsertExpression:
  val name: String = "insert-expression"
end InsertExpression
