package scoverage

import scala.tools.nsc.plugins.{PluginComponent, Plugin}
import scala.tools.nsc.Global
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import scala.reflect.internal.util.SourceFile
import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.internal.ModifierFlags
import java.io.File

/** @author Stephen Samuel */
class ScoveragePlugin(val global: Global) extends Plugin {

  override val name: String = "scoverage"
  override val description: String = "scoverage code coverage compiler plugin"
  val preComponent = new ScoveragePreComponent(global)
  val instrumentationComponent = new ScoverageInstrumentationComponent(global)
  override val components: List[PluginComponent] = List(preComponent, instrumentationComponent)

  override def processOptions(opts: List[String], error: String => Unit) {
    val options = new ScoverageOptions
    for ( opt <- opts ) {
      if (opt.startsWith("excludedPackages:")) {
        options.excludedPackages = opt.substring("excludedPackages:".length).split(";").map(_.trim).filterNot(_.isEmpty)
      } else if (opt.startsWith("dataDir:")) {
        options.dataDir = opt.substring("dataDir:".length)
      } else {
        error("Unknown option: " + opt)
      }
    }
    instrumentationComponent.setOptions(options)
  }

  override val optionsHelp: Option[String] = Some(Seq(
    "-P:scoverage:dataDir:<pathtodatadir>                  where the coverage files should be written\n",
    "-P:scoverage:excludedPackages:<regex>;<regex>         semicolon separated list of regexs for packages to exclude",
    "                                                      Any classes whose fully qualified name matches the regex will",
    "                                                      be excluded from coverage."
  ).mkString("\n"))
}

class ScoverageOptions {
  var excludedPackages: Seq[String] = Nil
  var dataDir: String = File.createTempFile("scoverage_find_temp_dir", ".tmp").getParent
}

class ScoveragePreComponent(val global: Global) extends PluginComponent with TypingTransformers with Transform {

  import global._

  override val phaseName: String = "scoverage-pre"
  override val runsAfter: List[String] = List("parser")
  override val runsBefore = List[String]("namer")

  override def newPhase(prev: scala.tools.nsc.Phase): Phase = new Phase(prev) {
    override def run(): Unit = {
      println("[scoverage]: Begin pre-instrumentation phase")
      super.run()
      println("[scoverage]: Pre-instrumentation complete")
    }
  }

  protected def newTransformer(unit: CompilationUnit): Transformer = new Transformer(unit)
  class Transformer(unit: global.CompilationUnit) extends TypingTransformer(unit) {
    override def transform(tree: Tree) = {
      tree match {
        case v: ValDef if v.mods.isFinal =>
          treeCopy.ValDef(v, v.mods.&~(ModifierFlags.FINAL), v.name, v.tpt, v.rhs)
        case _ =>
          super.transform(tree)
      }
    }
  }
}

class ScoverageInstrumentationComponent(val global: Global)
  extends PluginComponent
  with TypingTransformers
  with Transform {

  import global._

  val statementIds = new AtomicInteger(0)
  val coverage = new Coverage

  override val phaseName: String = "scoverage-instrumentation"
  override val runsAfter: List[String] = List("typer")
  override val runsBefore = List[String]("patmat")

  /**
   * Our options are not provided at construction time, but shortly after,
   * so they start as None.
   * You must call "setOptions" before running any commands that rely on
   * the options.
   */
  private var options: ScoverageOptions = new ScoverageOptions()
  private var coverageFilter: CoverageFilter = AllCoverageFilter

  def setOptions(options: ScoverageOptions): Unit = {
    this.options = options
    coverageFilter = new RegexCoverageFilter(options.excludedPackages)
  }

  override def newPhase(prev: scala.tools.nsc.Phase): Phase = new Phase(prev) {

    override def run(): Unit = {
      println("[scoverage]: Begin profiling phase")
      super.run()
      println("[scoverage]: Profiling completed: " + coverage.statements.size + " statements profiled")

      IOUtils.serialize(coverage, IOUtils.coverageFile(options.dataDir))
      println("[scoverage]: Written profile-file to " + IOUtils.coverageFile(options.dataDir))
      println("[scoverage]: Will write measurement data to " + options.dataDir)
    }
  }

  protected def newTransformer(unit: CompilationUnit): Transformer = new Transformer(unit)

  class Transformer(unit: global.CompilationUnit) extends TypingTransformer(unit) {

    import global._

    var location: Location = null

    /**
     * The 'start' of the position, if it is available, else -1
     * We cannot use 'isDefined' to test whether pos.start will work, as some
     * classes (e.g. scala.reflect.internal.util.OffsetPosition have
     * isDefined true, but throw on `start`
     */
    def safeStart(tree: Tree): Int = scala.util.Try(tree.pos.start).getOrElse(-1)
    def safeEnd(tree: Tree): Int = scala.util.Try(tree.pos.end).getOrElse(-1)
    def safeLine(tree: Tree): Int = if (tree.pos.isDefined) tree.pos.line else -1
    def safeSource(tree: Tree): Option[SourceFile] = if (tree.pos.isDefined) Some(tree.pos.source) else None

    def invokeCall(id: Int): Tree = {
      Apply(
        Select(
          Select(
            Ident("scoverage"),
            newTermName("Invoker")
          ),
          newTermName("invoked")
        ),
        List(
          Literal(
            Constant(id)
          ),
          Literal(
            Constant(options.dataDir)
          )
        )
      )
    }

    override def transform(tree: Tree) = process(tree)

    def transformIf(tree: Tree) = {
      instrument(process(tree), true)
    }

    def transformStatements(trees: List[Tree]): List[Tree] = trees.map(process)

    def transformCases(cases: List[CaseDef]): List[CaseDef] = {
      cases.map(c => {
        treeCopy.CaseDef(
          c, c.pat, process(c.guard), instrument(process(c.body))
        )
      })
    }

    def instrument(tree: Tree, branch: Boolean = false) = {
      safeSource(tree) match {
        case None =>
          println(s"[warn] Could not instrument [${tree.getClass.getSimpleName}/${tree.symbol}]. No position.")
          tree
        case Some(source) =>
          if (tree.pos.isDefined && !isStatementIncluded(tree.pos)) {
            tree
          } else {
            val id = statementIds.incrementAndGet
            val statement = MeasuredStatement(
              source.path,
              location,
              id,
              safeStart(tree),
              safeEnd(tree),
              safeLine(tree),
              tree.toString(),
              Option(tree.symbol).map(_.fullNameString).getOrElse("<nosymbol>"),
              tree.getClass.getSimpleName,
              branch
            )
            coverage.add(statement)

            val apply = invokeCall(id)
            val block = Block(List(apply), tree)
            localTyper.typed(atPos(tree.duplicate.pos)(block))
          }
      }
    }

    def isClassIncluded(symbol: Symbol): Boolean = {
      coverageFilter.isClassIncluded(symbol.fullNameString)
    }

    def isStatementIncluded(pos: Position): Boolean = {
      coverageFilter.isLineIncluded(pos)
    }

    def className(s: Symbol): String = {
      if (s.enclClass.isAnonymousFunction || s.enclClass.isAnonymousFunction)
        className(s.owner)
      else
        s.enclClass.nameString
    }

    def enclosingMethod(s: Symbol): String = {
      if (s.enclClass.isAnonymousFunction || s.enclClass.isAnonymousFunction)
        enclosingMethod(s.owner)
      else
        Option(s.owner.enclMethod.nameString).getOrElse("<none>")
    }

    def updateLocation(s: Symbol) {
      val classType = {
        if (s.owner.enclClass.isTrait) ClassType.Trait
        else if (s.owner.enclClass.isModule) ClassType.Object
        else ClassType.Class
      }
      location = Location(
        s.enclosingPackage.fullName,
        className(s),
        classType,
        enclosingMethod(s)
      )
    }

    def transformPartial(c: ClassDef): ClassDef = {
      treeCopy.ClassDef(
        c, c.mods, c.name, c.tparams,
        treeCopy.Template(
          c.impl, c.impl.parents, c.impl.self, c.impl.body.map {
            case d: DefDef if d.name.toString == "applyOrElse" =>
              d.rhs match {
                case Match(selector, cases) =>
                  treeCopy.DefDef(
                    d, d.mods, d.name, d.tparams, d.vparamss, d.tpt,
                    treeCopy.Match(
                      // note: do not transform last case as that is the default handling
                      d.rhs, selector, transformCases(cases.init) :+ cases.last
                    )
                  )
                case _ =>
                  println("Cannot instrument partial function apply. Please file bug report")
                  d
              }
            case other => other
          }
        )
      )
    }

    def debug(t: Tree) {
      import scala.reflect.runtime.{universe => u}
      println(t.getClass.getSimpleName + ": LINE " + safeLine(t) + ": " + u.showRaw(t))
    }

    def traverseApplication(t: Tree): Tree = {
      t match {
        case a: ApplyToImplicitArgs => treeCopy.Apply(a, traverseApplication(a.fun), transformStatements(a.args))
        case a: Apply => treeCopy.Apply(a, traverseApplication(a.fun), transformStatements(a.args))
        case a: TypeApply => treeCopy.TypeApply(a, traverseApplication(a.fun), transformStatements(a.args))
        case s: Select => treeCopy.Select(s, traverseApplication(s.qualifier), s.name)
        case i: Ident => i
        case t: This => t
        case other => process(other)
      }
    }

    def allConstArgs(args: List[Tree]) = args.forall(arg => arg.isInstanceOf[Literal] || arg.isInstanceOf[Ident])

    def process(tree: Tree): Tree = {
      tree match {

        /**
         * Object creation from new.
         * Ignoring creation calls to anon functions
         */
        case a: GenericApply if a.symbol.isConstructor && a.symbol.enclClass.isAnonymousFunction => tree
        case a: GenericApply if a.symbol.isConstructor => instrument(a)

        /**
         * When an apply has no parameters, or is an application of purely literals or idents
         * then we can simply instrument the outer call.
         * This will include calls to case apply.
         */
        case a: GenericApply if allConstArgs(a.args) => instrument(a)

        /**
         * Applications of methods with non trivial args means the args themselves
         * must also be instrumented
         */
        //todo remove once scala merges into Apply proper
        case a: ApplyToImplicitArgs =>
          instrument(treeCopy.Apply(a, traverseApplication(a.fun), transformStatements(a.args)))
        case a: Apply =>
          instrument(treeCopy.Apply(a, traverseApplication(a.fun), transformStatements(a.args)))
        case a: TypeApply =>
          instrument(treeCopy.TypeApply(a, traverseApplication(a.fun), transformStatements(a.args)))

        /** pattern match with syntax `Assign(lhs, rhs)`.
          * This AST node corresponds to the following Scala code:
          * lhs = rhs
          */
        case assign: Assign => treeCopy.Assign(assign, assign.lhs, process(assign.rhs))

        /** pattern match with syntax `Block(stats, expr)`.
          * This AST node corresponds to the following Scala code:
          * { stats; expr }
          * If the block is empty, the `expr` is set to `Literal(Constant(()))`.
          */
        case b: Block =>
          treeCopy.Block(b, transformStatements(b.stats), transform(b.expr))

        // special support to handle partial functions
        case c: ClassDef if c.symbol.isAnonymousFunction &&
          c.symbol.enclClass.superClass.nameString.contains("AbstractPartialFunction") =>
          if (isClassIncluded(c.symbol)) {
            transformPartial(c)
          } else {
            c
          }

        // scalac generated classes, we just instrument the enclosed methods/statments
        // the location would stay as the source class
        case c: ClassDef if c.symbol.isAnonymousClass || c.symbol.isAnonymousFunction =>
          if (isClassIncluded(c.symbol))
            super.transform(tree)
          else {
            c
          }

        case c: ClassDef =>
          if (isClassIncluded(c.symbol)) {
            updateLocation(c.symbol)
            super.transform(tree)
          } else {
            c
          }

        // skip macros
        case d: DefDef if d.symbol != null && d.symbol.annotations.size > 0
          && d.symbol.annotations.head.atp.typeSymbol.nameString == "macroImpl" =>
          tree.duplicate

        // todo do we really want to ignore?
        case d: DefDef if d.symbol.isPrimaryConstructor => tree
        // todo definitely want to instrument user level constructors
        case d: DefDef if tree.symbol.isConstructor => tree

        /**
         * Case class accessors for vals
         * EG for case class CreditReject(req: MarketOrderRequest, client: ActorRef)
         * <stable> <caseaccessor> <accessor> <paramaccessor> def req: com.sksamuel.scoverage.samples.MarketOrderRequest
         * <stable> <caseaccessor> <accessor> <paramaccessor> def client: akka.actor.ActorRef
         */
        case d: DefDef if d.symbol.isCaseAccessor => tree

        // Compiler generated case apply and unapply. Ignore these
        case d: DefDef if d.symbol.isCaseApplyOrUnapply => tree

        /**
         * Lazy stable DefDefs are generated as the impl for lazy vals.
         */
        case d: DefDef if d.symbol.isStable && d.symbol.isGetter && d.symbol.isLazy =>
          updateLocation(d.symbol)
          treeCopy.DefDef(d, d.mods, d.name, d.tparams, d.vparamss, d.tpt, process(d.rhs))

        /**
         * Stable getters are methods generated for access to a top level val.
         * Should be ignored as this is compiler generated code.
         *
         * Eg
         * <stable> <accessor> def MaxCredit: scala.math.BigDecimal = CreditEngine.this.MaxCredit
         * <stable> <accessor> def alwaysTrue: String = InstrumentLoader.this.alwaysTrue
         */
        case d: DefDef if d.symbol.isStable && d.symbol.isGetter => tree

        /** Accessors are auto generated setters and getters.
          * Eg
          * <accessor> private def _clientName: String =
          * <accessor> def cancellable: akka.actor.Cancellable = PriceEngine.this.cancellable
          * <accessor> def cancellable_=(x$1: akka.actor.Cancellable): Unit = PriceEngine.this.cancellable = x$1
          */
        case d: DefDef if d.symbol.isAccessor => tree

        // was `abstract' for members | trait is virtual
        case d: DefDef if tree.symbol.isDeferred => tree

        /** eg
          * override <synthetic> def hashCode(): Int
          * <synthetic> def copy$default$1: com.sksamuel.scoverage.samples.MarketOrderRequest
          * <synthetic> def <init>$default$3: Option[org.joda.time.LocalDate] @scala.annotation.unchecked.uncheckedVariance = scala.None
          */
        case d: DefDef if d.symbol.isSynthetic => tree

        /** Match all remaining def definitions
          *
          * If the return type is not specified explicitly (i.e. is meant to be inferred),
          * this is expressed by having `tpt` set to `TypeTree()` (but not to an `EmptyTree`!).
          */
        case d: DefDef =>
          updateLocation(d.symbol)
          treeCopy.DefDef(d, d.mods, d.name, d.tparams, d.vparamss, d.tpt, process(d.rhs))

        case EmptyTree =>
          super.transform(tree)

        // handle function bodies. This AST node corresponds to the following Scala code: vparams => body
        case f: Function =>
          treeCopy.Function(tree, f.vparams, process(f.body))

        case _: Ident => tree

        case i: If =>
          treeCopy.If(i, process(i.cond), transformIf(i.thenp), transformIf(i.elsep))

        case _: Import => tree

        // labeldefs are never written natively in scala
        case l: LabelDef =>
          treeCopy.LabelDef(tree, l.name, l.params, transform(l.rhs))

        // profile access to a literal for function args todo do we need to do this?
        case l: Literal => instrument(l)

        // pattern match clauses will be instrumented per case
        case Match(clause: Tree, cases: List[CaseDef]) =>
          treeCopy.Match(tree, instrument(clause), transformCases(cases))

        // a synthetic object is a generated object, such as case class companion
        case m: ModuleDef if m.symbol.isSynthetic => super.transform(tree)

        // user defined objects
        case m: ModuleDef =>
          if (isClassIncluded(m.symbol)) {
            updateLocation(m.symbol)
            super.transform(tree)
          }
          else
            m

        /**
         * match with syntax `New(tpt)`.
         * This AST node corresponds to the following Scala code:
         *
         * `new` T
         *
         * This node always occurs in the following context:
         *
         * (`new` tpt).<init>[targs](args)
         *
         * For example, an AST representation of:
         *
         * new Example[Int](2)(3)
         *
         * is the following code:
         *
         * Apply(
         * Apply(
         * TypeApply(
         * Select(New(TypeTree(typeOf[Example])), nme.CONSTRUCTOR)
         * TypeTree(typeOf[Int])),
         * List(Literal(Constant(2)))),
         * List(Literal(Constant(3))))
         *
         * We can ignore New as they should be profiled by the outer select.
         */
        case n: New => super.transform(n)

        case p: PackageDef =>
          if (isClassIncluded(p.symbol)) treeCopy.PackageDef(p, p.pid, transformStatements(p.stats))
          else p

        // This AST node corresponds to the following Scala code:  `return` expr
        case r: Return =>
          treeCopy.Return(r, transform(r.expr))

        /** pattern match with syntax `Select(qual, name)`.
          * This AST node corresponds to the following Scala code:
          *
          * qualifier.selector
          *
          * Should only be used with `qualifier` nodes which are terms, i.e. which have `isTerm` returning `true`.
          * Otherwise `SelectFromTypeTree` should be used instead.
          *
          * foo.Bar // represented as Select(Ident(<foo>), <Bar>)
          * Foo#Bar // represented as SelectFromTypeTree(Ident(<Foo>), <Bar>)
          */
        case s: Select if location == null => tree

        /**
         * I think lazy selects are the LHS of a lazy assign.
         * todo confirm we can ignore
         */
        case s: Select if s.symbol.isLazy => tree

        case s: Select => instrument(treeCopy.Select(s, traverseApplication(s.qualifier), s.name))

        case s: Super => tree

        // This AST node corresponds to the following Scala code:    qual.this
        case t: This => super.transform(tree)

        // This AST node corresponds to the following Scala code:    `throw` expr
        case t: Throw => instrument(tree)

        // This AST node corresponds to the following Scala code: expr: tpt
        case t: Typed => super.transform(tree)

        // instrument trys, catches and finally as seperate blocks
        case Try(t: Tree, cases: List[CaseDef], f: Tree) =>
          treeCopy.Try(tree, instrument(process(t), true), transformCases(cases), instrument(process(f), true))

        // type aliases, type parameters, abstract types
        case t: TypeDef => super.transform(tree)

        case t: Template =>
          treeCopy.Template(tree, t.parents, t.self, transformStatements(t.body))

        case _: TypeTree => super.transform(tree)

        /**
         * We can ignore lazy val defs as they are implemented by a generated defdef
         */
        case v: ValDef if v.symbol.isLazy => tree

        /**
         * <synthetic> val default: A1 => B1 =
         * <synthetic> val x1: Any = _
         */
        case v: ValDef if v.symbol.isSynthetic => tree

        /**
         * Vals declared in case constructors
         */
        case v: ValDef if v.symbol.isParamAccessor && v.symbol.isCaseAccessor => tree

        // we need to remove the final mod so that we keep the code in order to check its invoked
        case v: ValDef if v.mods.isFinal =>
          updateLocation(v.symbol)
          treeCopy.ValDef(v, v.mods.&~(ModifierFlags.FINAL), v.name, v.tpt, process(v.rhs))

        /**
         * This AST node corresponds to any of the following Scala code:
         *
         * mods `val` name: tpt = rhs
         * mods `var` name: tpt = rhs
         * mods name: tpt = rhs        // in signatures of function and method definitions
         * self: Bar =>                // self-types
         *
         * For user defined value statements, we will instrument the RHS.
         *
         * This includes top level non-lazy vals. Lazy vals are generated as stable defs.
         */
        case v: ValDef =>
          updateLocation(v.symbol)
          treeCopy.ValDef(tree, v.mods, v.name, v.tpt, process(v.rhs))

        case _ =>
          println("BUG: Unexpected construct: " + tree.getClass + " " + tree.symbol)
          super.transform(tree)
      }
    }
  }
}


