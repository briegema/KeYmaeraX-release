package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.btactics.AnonymousLemmas.remember
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory.anon
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.helpers.DifferentialHelper
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.infrastruct.{PosInExpr, Position}
import edu.cmu.cs.ls.keymaerax.infrastruct.Augmentors._

import scala.collection.immutable.Nil

/**
  * Provides support for generating switched system models
  * under various switching mechanisms.
  *
  * Also provides proof automation for stability proofs
  *
  */

object SwitchedSystems {

  private val namespace = "switchedsys"

  //An ADT encoding canonical formats for switched systems
  sealed trait SwitchedSystem {
    // Represent this switched system as a hybrid program
    def asProgram : Program
    def asClockedProgram(t : Variable) : Program

    val cvars : List[Variable] // the default continuous state variables
    val vars : List[Variable]  // any variable appearing anywhere in the system

    val odes : List[ODESystem] // the underlying ODEs
  }

  // Unfold a choice of the form hp1++hp2++hp3 into a list (the order of association is important)
  private def choiceList(p : Program) : List[Program] = {
    p match {
      case Choice(o,rest) => o::choiceList(rest)
      case _  => List(p)
    }
  }

  // Unfold a compose of the form hp1;hp2;hp3 into a list (the order of association is important)
  private def composeList(p : Program) : List[Program] = {
    p match {
      case Compose(o,rest) => o::composeList(rest)
      case _  => List(p)
    }
  }

  /**  State dependent switching
    *  Switching between a list of ODEs (with domains)
    */
  case class StateDependent(odes: List[ODESystem]) extends SwitchedSystem {

    require(odes.nonEmpty, "State-dependent switched system requires at least 1 ODE")

    override def asProgram = asProgram(None)
    override def asClockedProgram(t:Variable) = asProgram(Some(t))

    private def asProgram(topt: Option[Variable]) = {
      require(topt.isEmpty || !vars.contains(topt.get), "Time variable " + topt.get + " must be fresh")

      val todes: List[Program] = topt match {
        case None => odes
        case Some(t) =>
          odes.map(ode =>
            ODESystem(DifferentialProduct(AtomicODE(DifferentialSymbol(t), Number(1)), ode.ode), ode.constraint))
      }

      // arbitrary switching choice is modeled by dL's ++
      val body = todes.reduceRight(Choice)

      // repeated switching is modeled by dL's *
      Loop(body)
    }

    override val cvars : List[Variable] = odes.map(ode => StaticSemantics.boundVars(ode).toSet.toList.filterNot(StaticSemantics.isDifferential(_))).flatten.distinct
    override val vars : List[Variable] = odes.map(ode => StaticSemantics.vars(ode).toSet.toList.filterNot(StaticSemantics.isDifferential(_))).flatten.distinct
  }

  // Remove the time variable
  private def stripTimer(ode: DifferentialProgram, t : Variable) : DifferentialProgram = {
    ode match {
      case DifferentialProduct(l,r) if l == AtomicODE(DifferentialSymbol(t), Number(1)) => r
      case _ =>  throw new IllegalArgumentException("Unable to strip time variable " + t + " in ODE " + ode)
    }
  }

  private def stripTimer(odes:List[ODESystem], topt:Option[Variable]) : List[ODESystem] = {
    topt match {
      case None => odes
      case Some(t) =>
        odes.map( ode => ODESystem(stripTimer(ode.ode,t),ode.constraint))
    }
  }

  def stateDependentFromProgram(p : Program, topt:Option[Variable]) : StateDependent = {
    p match {
      case Loop(choices) =>
        val odes = choiceList(choices)
        if(odes.forall(_.isInstanceOf[ODESystem]))
          StateDependent(stripTimer(odes.map(_.asInstanceOf[ODESystem]),topt))
        else
          throw new IllegalArgumentException("Unexpected program found in " + odes)
      case _ =>
        throw new IllegalArgumentException("Unable to parse program as state-dependent switched system " + p)
    }
  }

  /** Controlled switching models
    *
    * @param initopt optional initialization program
    * @param modes   list of modes, each mode consisting of:
    *                name: String (representing constant function name())
    *                ode:  ODESystem (continuous dynamics)
    *                transitions : List[(String,Program)]
    *                   (transitions p->q where program a is executed along the transition, e.g., a guard and/or reset map)
    * @param u       the mode control variable u
    */
  case class Controlled(initopt : Option[Program],
                        modes   : List[(String,ODESystem,List[(String,Program)])],
                        u       : Variable) extends SwitchedSystem {

    // Explicit names of the modes
    val names : List[String] = modes.map(_._1)

    // Explicit ODEs of the modes
    override val odes : List[ODESystem] = modes.map(_._2)

    // Explicit list of transitions
    val transitions: List[List[(String,Program)]] = modes.map(_._3)

    // Default approximation of the continuous state variables
    override val cvars : List[Variable] = odes.flatMap(ode => StaticSemantics.boundVars(ode).toSet.toList.filterNot(StaticSemantics.isDifferential(_))).distinct

    // All variables used in the program (except u)
    private val varsPre =   (
      //Init
      StaticSemantics.vars(initopt.getOrElse(Test(True))).toSet.toList ++
        //Control
        transitions.flatten.map(p => StaticSemantics.vars(p._2).toSet.toList).flatten ++
        //Plant
        odes.map(ode => StaticSemantics.vars(ode).toSet.toList.filterNot(StaticSemantics.isDifferential(_))).flatten
      ).distinct

    require(!varsPre.contains(u), "Control variable " + u + " must be fresh")
    override val vars : List[Variable] = u::varsPre

    // Some basic consistency checks
    require(modes.nonEmpty, "Controlled switching requires at least 1 mode")

    require(names.distinct.length == modes.length, "Controlled switching requires distinct names for each mode")


    transitions.map(_.map( f =>
        require(names.contains(f._1), "Unable to jump to undefined mode: " + f._1)
    ))

    override def asProgram : Program = asProgram(None)
    override def asClockedProgram(t:Variable) : Program = asProgram(Some(t))

    private def asProgram(topt: Option[Variable]) = {
      require(topt.isEmpty || !vars.contains(topt.get), "Time variable " + topt.get + " must be fresh")

      val todes: List[Program] = topt match {
        case None => odes
        case Some(t) =>
          odes.map(ode =>
            ODESystem(DifferentialProduct(AtomicODE(DifferentialSymbol(t), Number(1)), ode.ode), ode.constraint))
      }

      // base initializer
      // {u:=1 ; u :=2 ; ... } ; user-given-initializer (if any)
      val initPre = names.map( n => Assign(u,FuncOf(Function(n, None, Unit, Real), Nothing))).reduceRight(Choice)
      val init = initopt match {
        case None => initPre
        case Some(i) => Compose(initPre,i)
      }

      // control
      val trans = transitions.map(mt =>
        mt.map( f =>
          Compose(f._2, Assign(u,FuncOf(Function(f._1, None, Unit, Real), Nothing)))
        ).foldRight(Assign(u,u):Program)(Choice) //self loops
      )

      val ctrl = (names zip trans).map(np =>
        Compose(Test(Equal(u,FuncOf(Function(np._1, None, Unit, Real), Nothing))), np._2)).reduceRight(Choice)

      // plant
      val plant = (names zip todes).map(np =>
        Compose(Test(Equal(u,FuncOf(Function(np._1, None, Unit, Real), Nothing))), np._2)).reduceRight(Choice)

      // repeated switching is modeled by dL's *
      Compose(init,Loop(Compose(ctrl,plant)))
    }
  }

  private def parseInit(init : Program) : (Variable,List[String],Option[Program]) = {

    val (initL, initR) = init match {
      case Compose(l,r) => (l, Some(r))
      case _ => (init,None)
    }

    val choices = choiceList(initL)

    if(!choices.forall(_.isInstanceOf[Assign]))
      throw new IllegalArgumentException("Unable to parse initializer for controlled switching at: "+init)
    val assigns = choices.map(c => c.asInstanceOf[Assign])
    val ctrl = assigns.map(f => f.x).distinct

    if(ctrl.length != 1)
      throw new IllegalArgumentException("Unable to parse initializer for controlled switching at: "+init)

    //todo: more precise check needed here
    val rhs = assigns.map(f=> f.e.asInstanceOf[FuncOf].func.name)
    (ctrl.head, rhs, initR)
  }

  private def parsePlant(plant : Program) : (Variable,List[String],List[ODESystem]) = {
    val choices = choiceList(plant)

    val uvfo = choices.map( c => c match {
      case Compose(Test(Equal(uv:Variable, FuncOf(f, Nothing))), o: ODESystem) => (uv,f,o)
      case _ => throw new IllegalArgumentException("Unable to parse plant for controlled switching at: "+ c)
    }
    )

    (uvfo.head._1,uvfo.map(_._2.name),uvfo.map(_._3))
  }

  private def parseCtrl(ctrl : Program) : (Variable, List[String],List[List[(String,Program)]]) = {
    val choices = choiceList(ctrl)

    val uvfo = choices.map( c => c match {
      case Compose(Test(Equal(uv:Variable, FuncOf(f, Nothing))), o) => (uv,f,o)
      case _ => throw new IllegalArgumentException("Unable to parse ctrl for controlled switching at: "+ c)
    }
    )

    val transitions = uvfo.map( f => {
      val tchoices = choiceList(f._3).dropRight(1)
      tchoices.map( tc => tc match {
        case Compose( prog, Assign(_:Variable, FuncOf(f, Nothing)) ) =>
          (f.name, prog)
        case _ => throw new IllegalArgumentException("Unable to parse ctrl for controlled switching at: "+ tc)
      })
      }
    )


    (uvfo.head._1,uvfo.map(_._2.name),transitions)
  }

  def controlledFromProgram(p : Program, topt:Option[Variable]) : Controlled = {
    p match {
      case Compose(init,Loop(Compose(ctrl,plant))) => {
        val (u1, modes1, initopt) = parseInit(init)
        val (u2, modes2, odes) = parsePlant(plant)
        val (u3, modes3, transitions) = parseCtrl(ctrl)

        // todo: more consistency checks
        // println(u1,modes1,initopt)
        // println(u2,modes2,odes)
        // println(u3,modes3,transitions)

        Controlled(initopt, (modes1,stripTimer(odes,topt),transitions).zipped.toList, u1)
      }
      case _ =>
        throw new IllegalArgumentException("Unable to parse program for controlled switching " + p)
    }
  }

  case class Guarded(modes : List[(String,ODESystem,List[(String,Formula)])], u : Variable) extends SwitchedSystem {

    val underlyingControlled = Controlled(None, modes.map( t => (t._1,t._2,t._3.map( s => (s._1,Test(s._2))))) , u)

    override def asProgram : Program = underlyingControlled.asProgram
    override def asClockedProgram(t : Variable) : Program = underlyingControlled.asClockedProgram(t)

    override val cvars : List[Variable] = underlyingControlled.cvars
    override val vars : List[Variable]  = underlyingControlled.vars
    override val odes : List[ODESystem] = underlyingControlled.odes
  }

  def guardedFromProgram(p : Program, topt:Option[Variable]) : Guarded = {
    val c = controlledFromProgram(p, topt)

    if(c.initopt.nonEmpty)
      throw new IllegalArgumentException("Unable to parse program for guarded switching at: " + c.initopt)

    val modes = c.modes.map(t =>
      (t._1,t._2,
        t._3.map( s => (s._1, s._2 match {
          case Test(g) => g
          case _ => throw new IllegalArgumentException("Unable to parse program for guarded switching at: " + s._2)
        }
        ))
      )
    )
    Guarded(modes,c.u)
  }

  case class Timed(modes : List[(String, DifferentialProgram, Option[Term],List[(String, Option[Term])])], u : Variable, timer : Variable) extends SwitchedSystem {

    //todo: timer should be fresh in ODE RHS and terms
    val underlyingControlled = Controlled(
      Some(Assign(timer,Number(0))),
      modes.map( t => (t._1,
        ODESystem(DifferentialProduct(AtomicODE(DifferentialSymbol(timer),Number(1)), t._2),
          t._3 match { case None => True case Some(tt) => LessEqual(timer,tt)}),
        t._4.map( s => (s._1,
          Compose(Test(s._2 match{ case None => True case Some(ss) => GreaterEqual(timer,ss)}),Assign(timer,Number(0))))))) , u)

    override def asProgram  : Program = underlyingControlled.asProgram
    override def asClockedProgram(t : Variable) : Program = underlyingControlled.asClockedProgram(t)

    override val cvars : List[Variable] = underlyingControlled.cvars.filterNot(p => p == timer)
    override val vars : List[Variable] = underlyingControlled.vars
    override val odes : List[ODESystem] = underlyingControlled.odes
  }

  def timedFromProgram(p : Program, topt:Option[Variable]) : Timed = {
    val c = controlledFromProgram(p, topt)

    val timer = c.initopt match {
      case Some(Assign(t:Variable, n: Number)) if n.value == 0 => t
      case _ => throw new IllegalArgumentException("Unable to parse program for timed switching at: " + c.initopt)
    }

    val modes = c.modes.map(t =>
      (t._1,
        stripTimer(t._2.ode,timer),
        t._2.constraint match {case True => None case LessEqual(t:Variable,bound) if t == timer => Some(bound)},
        t._3.map( s => (s._1, s._2 match {
          case Compose(Test(True),Assign(t:Variable,n)) if t == timer => None
          case Compose(Test(GreaterEqual(t1:Variable,bound)),Assign(t2,n: Number)) if t1 == t2 && t2 == timer && n.value == 0 => Some(bound)
          case _ => throw new IllegalArgumentException("Unable to parse program for timed switching at: " + s._2)
        }
        ))
      )
    )

    Timed(modes,c.u, timer)
  }

  // TODO: Collects all the parsers in some canonical order, probably not right way to do this, use options instead
  def switchedFromProgram(p : Program, topt:Option[Variable]) : SwitchedSystem = {
    try { stateDependentFromProgram(p, topt) }  catch{ case e: IllegalArgumentException =>
    try { guardedFromProgram(p, topt) } catch{ case e: IllegalArgumentException =>
    try { timedFromProgram(p, topt) } catch{ case e: IllegalArgumentException =>
    try { controlledFromProgram(p, topt) } catch{ case e: IllegalArgumentException =>
      throw new IllegalArgumentException("Unable to parse program as switched system: " + p)
    }}}}
  }

  // Standard stability specification for a SwitchedSystem
  def stabilitySpec(ss: SwitchedSystem): Formula = {

    // Fixed names for now
    val eps = Variable("eps")
    val del = Variable("del")
    val freshVars = List(eps,del)

    if (ss.vars.intersect(freshVars).nonEmpty)
      throw new IllegalArgumentException("Switched system must not mention variables " + freshVars +" for stability specification")

    val normsq = ss.cvars.map(e => Power(e, Number(2))).reduceLeft(Plus) // ||x||^2

    val init = Less(normsq, Power(del, Number(2)))

    val fmlbody = Imply(init,
      Box(ss.asProgram, Less(normsq, Power(eps, Number(2))))
    )

    val qbody = ss.cvars.foldRight(fmlbody: Formula)((v, f) => Forall(v :: Nil, f))

    Forall(eps :: Nil, Imply(Greater(eps, Number(0)), // \forall eps > 0
      Exists(del :: Nil, And(Greater(del, Number(0)), // \exists del > 0
        qbody
      ))))
  }

  // Standard global pre-attractivity specification for a SwitchedSystem
  def attractivitySpec(ss: SwitchedSystem): Formula = {

    // Fixed names for now
    val eps = Variable("eps")
    val del = Variable("del")
    val tVar = Variable("t_")
    val TVar = Variable("T_")
    val freshVars = List(eps,del,tVar,TVar)

    if (ss.vars.intersect(freshVars).nonEmpty)
      throw new IllegalArgumentException("Switched system must not mention variables " + freshVars +" for attractivity specification")

    val normsq = ss.cvars.map(e => Power(e, Number(2))).reduceLeft(Plus) // ||x||^2

    val init = Less(normsq, Power(del, Number(2)))

    val fmlbody = Imply(init,
      Box(Compose(Assign(tVar,Number(0)),ss.asClockedProgram(tVar)),
        Imply(GreaterEqual(tVar,TVar), Less(normsq, Power(eps, Number(2)))))
    )

    val qbody = ss.cvars.foldRight(fmlbody: Formula)((v, f) => Forall(v :: Nil, f))

    Forall(eps :: Nil, Imply(Greater(eps, Number(0)), // \forall eps > 0
      Forall(del :: Nil, Imply(Greater(del, Number(0)), // \forall del > 0
        Exists(TVar :: Nil, And(GreaterEqual(TVar, Number(0)), // \exists T >= 0
        qbody
      ))))))
  }

  // CLF tactics
  private def stripTillBox(f : Formula) : Program = {
    f match {
      case Forall(_, r) => stripTillBox(r)
      case Exists(_, r) => stripTillBox(r)
      case And(_, r) => stripTillBox(r)
      case Imply(_, r) => stripTillBox(r)
      case Box(p, r) => p
      case _ => ???
    }
  }

  def proveStabilityCLF (lyap: Term): DependentPositionTactic = anon((pos: Position, seq: Sequent) => {

    if (!(pos.isTopLevel && pos.isSucc))
      throw new IllFormedTacticApplicationException("proveStabilityCLF: position " + pos + " must point to a top-level succedent position")

    val fopt = seq.sub(pos)
    val prog = fopt match {
      case Some(f) => stripTillBox(f.asInstanceOf[Formula])
      case _ => throw new IllFormedTacticApplicationException("proveStabilityCLF: position " + pos + " must point to a valid top-level succedent position")
    }

    val ss = switchedFromProgram(prog, None)

    proveStabilityCLF(lyap, ss, seq.ante.length, pos)
  })

  // Internal CLF tactic
  private def proveStabilityCLF (lyap: Term, ss: SwitchedSystem, apos: Integer, pos : Position) : BelleExpr = {
    require( StaticSemantics.freeVars(lyap).toSet.subsetOf(ss.cvars.toSet), "Common Lyapunov function should only mention "+ss.cvars+" free")
    val eps = Variable("eps")
    val del = Variable("del")
    val epssq = Power(eps, Number(2))
    val delsq = Power(del, Number(2))

    val w = Variable("w_")

    val cvars: List[Variable] = ss.cvars
    val normsq = cvars.map(e => Power(e, Number(2))).reduceLeft(Plus) // ||x||^2

    val init = Less(normsq, Power(del, Number(2)))

    // w serves as an lower bound on V for ||x||=eps
    val epsbound =
      cvars.foldRight(Imply(Equal(normsq, epssq), GreaterEqual(lyap, w)): Formula)((v, f) => Forall(v :: Nil, f))
    val epsw = Exists(w :: Nil,
      And(Greater(w, Number(0)),
        epsbound
      ))

    // w serves as an upper bound on V for ||x||<del
    val delw = And(And(Greater(del, Number(0)), Less(del, eps)),
      cvars.foldRight(Imply(Less(normsq, delsq), Less(lyap, w)): Formula)((v, f) => Forall(v :: Nil, f))
    )

    // The relevant tactic for a single ODE
    // Assumes antecedent has the form:
    // assums, \forall x ( |x|=eps -> lyap >= w), lyap < w, |x| < eps
    // succedent has the form:
    // assums, [ODE] (lyap < w & |x| < eps)
    val odetac =
      dC(Less(lyap, w))(pos) < (
        DifferentialTactics.dCClosure('Rlast) < (
          implyRiLast & implyRiLast & cohideR('Rlast) & QE,
          hideL('Llast) & hideL('Llast) & implyRiLast & cohideR('Rlast) &
          implyR(1) &
          DW(1) & abstractionb(1) & SaturateTactic(allR(1)) & SaturateTactic(allL(-1)) & QE
        ),
        hideL('Llast) & implyRiLast & hideL('Llast) & implyR('Rlast) & dI('full)('Rlast)
      )

    val inv = And(Less(lyap, w), Less(normsq, epssq))

    // Skip over [a;b]P to [b]P
    val genSkip = composeb(pos) & generalize(inv)(pos) <(V(pos) & prop, skip)

    // Continuation
    val conttac = ss match {
      case StateDependent(odes) =>
        loop(inv)(pos) <(
          prop,
          prop,
          implyRi & implyR(1) & andL('Llast) &
          (choiceb('Rlast) & andR('Rlast) <(odetac, skip))*(odes.length-1) & //split per control choice
          odetac
        )
      // todo: would be really nice if we can just pattern match on "everything with an underlyingControlled, including Controlled itself"
      case Controlled(initopt, modes, u) =>
        composeb(pos) &
          abstractionb(pos) & SaturateTactic(allR(pos)) &
          loop(inv)(pos) <(
            prop,
            prop,
            implyRi & implyR(1) & andL('Llast) &
            composeb('Rlast) & abstractionb('Rlast) & SaturateTactic(allR('Rlast)) &
              (choiceb('Rlast) & andR('Rlast) <(
              composeb('Rlast) & abstractionb('Rlast) & odetac,
              skip)) * (modes.length-1) &
              composeb('Rlast) & abstractionb('Rlast) & odetac
            )
    }

    allR(pos) & implyR(pos) &
      cutR(epsw)(pos) < (
        QE,
        implyR(pos) & existsL('Llast) & andL('Llast) &
          existsRmon(delw)(pos) < (
            hideL('Llast) & QE, //hide the \forall x ( ...) that was just cut
            // Get rid of some unused assumptions now
            hideL(-(apos + 1)) & hideL(-(apos + 1)) & andL(-(apos + 2)) & andL(-(apos + 2)) &
            andR(pos) < (
              id,
              (allR(pos) * cvars.length) & implyR(pos) & // strip forall x (d^2 < del^2 -> )
              (allL(-(apos + 2)) * cvars.length) & implyL(-(apos + 2)) < (
                id,
                cutR(Less(normsq, epssq))(pos) < (
                  (implyRi()(AntePos(apos + 2), pos.checkSucc) * 3) & cohideR(pos) & byUS(edswap),
                  (hideL(-(apos + 3)) * 3) & implyR(pos) &
                    conttac
                )
              )
            )
          )
      )

  }

  def proveAttractivityCLF (lyap: Term): DependentPositionTactic = anon((pos: Position, seq: Sequent) => {

    if (!(pos.isTopLevel && pos.isSucc))
      throw new IllFormedTacticApplicationException("proveAttractivityCLF: position " + pos + " must point to a top-level succedent position")

    val progPre = seq.sub(pos) match{
      case Some(f:Formula) => stripTillBox(f)
      case _ => ???
    }

    val (t,prog) = progPre match {
      case Compose(Assign(t,n), p) => (t,p)
      case _ => ???
    }

    require(t == Variable("t_"), "TODO: remove this requirement")

    val ss = switchedFromProgram(prog, Some(t))

    proveAttractivityCLF(lyap, ss, seq.ante.length, pos)
  })

  private def implyRiLast : DependentTactic = anon { (sequent: Sequent) => {
    implyRi()(AntePos(sequent.ante.length-1), SuccPos(sequent.succ.length-1))
  }}

  // Internal CLF tactic
  private def proveAttractivityCLF (lyap: Term, ss: SwitchedSystem, apos: Integer, pos : Position) : BelleExpr = {

    require( StaticSemantics.freeVars(lyap).toSet.subsetOf(ss.cvars.toSet), "Common Lyapunov function should only mention "+ss.cvars+" free")

    // Fixed names for now
    val eps = Variable("eps")
    val del = Variable("del")
    val tVar = Variable("t_")
    val TVar = Variable("T_")
    val epssq = Power(eps, Number(2))
    val delsq = Power(del, Number(2))

    val w = Variable("w_")
    val u = Variable("u_")
    val kName = "k_"
    val k = Variable(kName)

    val cvars: List[Variable] = ss.cvars
    val normsq = cvars.map(e => Power(e, Number(2))).reduceLeft(Plus) // ||x||^2

    val init = Less(normsq, Power(del, Number(2)))

    // w serves as an upper bound on V for ||x||<del
    val delw = Exists(w :: Nil,
      And(Greater(w, Number(0)),
        cvars.foldRight(Imply(Less(normsq, delsq), Less(lyap, w)): Formula)((v, f) => Forall(v :: Nil, f))))

    // u is internal sublevel set in ||x|| < eps
    val epsu = Exists(u::Nil,
      And(Greater(u,Number(0)),
      cvars.foldRight(Imply(And( /* True */ Less(lyap, w), GreaterEqual(normsq, epssq)), GreaterEqual(lyap, u)): Formula)((v, f) => Forall(v :: Nil, f)))
    )

    val kis =
      (0 to ss.odes.length-1).map(i => Variable(kName,Some(i))).toList

    val derbod = ss.odes.zip(kis).map( odei => {
      val ki = odei._2
      Less(DifferentialHelper.simplifiedLieDerivative(odei._1.ode, lyap, ToolProvider.simplifierTool()),ki)})

    val derPair : List[(Formula,Formula)] = ss.odes.zip(kis).map( odei => {
      val ki = odei._2
      val ld = DifferentialHelper.simplifiedLieDerivative(odei._1.ode, lyap, ToolProvider.simplifierTool())
      val bod = Less(ld,ki )
      (Exists(ki :: Nil,
        And(Less(ki,Number(0)),
        cvars.foldRight(Imply(
          And(odei._1.constraint, And(GreaterEqual(lyap, u), /* True */ Less(lyap, w))), bod): Formula)((v, f) => Forall(v :: Nil, f)))), bod)
    })

    val derivatives = derPair.map(_._1)
    val derbods = derPair.map(_._2)

    val derFml = derivatives.reduceRight(And(_,_))

    // k is an upper bound on all kis
    val kMin = Exists(k :: Nil,
      And(Less(k,Number(0)), kis.map( ki => LessEqual(ki,k)).reduceRight(And) )
    )

    val Tchoice = And(GreaterEqual(TVar,Number(0)),LessEqual(Plus(w,Times(k,TVar)),u))

    // w serves as an lower bound on V for ||x||=eps
    val epsbound =
      cvars.foldRight(Imply(Equal(normsq, epssq), GreaterEqual(lyap, w)): Formula)((v, f) => Forall(v :: Nil, f))
    val epsw = Exists(w :: Nil,
      And(Greater(w, Number(0)),
        epsbound
      ))


    // The relevant tactic for a single ODE
    // Assumes antecedent has the form:
    // assums, k_i < 0, \forall x ( Q&V>=U&V<W -> V' < k_i), k < 0, k <= k_i, lyap < w, lyap >= u -> lyap < w + k * t
    // Succedent has form:
    // assums, [ODE](lyap< W & ...)
    def odetac (bd:Formula) : BelleExpr =
    dC(Less(lyap, w))(pos) < (
      boxAnd('Rlast) & andR('Rlast)<(
        DifferentialTactics.diffWeakenG('Rlast) & prop,
        useAt(Ax.DCC, PosInExpr(1::Nil))('Rlast) & andR('Rlast) <(
          //here, we don't need assums
          (implyRiLast * 5) & cohideOnlyR('Rlast) & cohideOnlyL('Llast) &
            (implyR(1) * 5) &
            dC(bd)(1)<( //maybe DR instead?
              implyRiLast & hideL('Llast) & implyRiLast & cohideR(1) & implyR(1) & implyR(1) & dI('full)(1),
              DW(1) & abstractionb(1) & hideL(-1) & SaturateTactic(allR(1)) & SaturateTactic(allL(-1)) & prop
            ),
          (hideL('Llast)*6) & dW('Rlast) & implyR(1) & dI('full)(1)
        )
      ),
      hideL('Llast) & implyRiLast & (hideL('Llast)*4) & implyR('Rlast) & dI('full)('Rlast)
    )

    val inv = And(Less(lyap, w), Imply(GreaterEqual(lyap,u),Less(lyap, Plus(w,Times(k,tVar)))))

    // Continuation
    val conttac = ss match {
      case StateDependent(odes) =>
        loop(inv)(pos) <(
          cutR(Equal(tVar,Number(0)))(pos) <(
            id,
            implyR(pos) & exhaustiveEqL2R('Llast) & SimplifierV3.simplify(pos) & closeT
          ),
          //Note: for these next two branches, the "loop" branch is moved to the first succedent position
          ArithmeticSimplification.hideFactsAbout(kis) &
          hideL('Llast) & implyRiLast & implyRiLast & (allL('Llast) * cvars.length) & implyL('Llast) <(
            andR('Rlast) <(prop, SaturateTactic(hideL('L)) & QE),
            andL(-1) & implyL('Llast) <( id, QE) //todo: this last QE can be done more manually
          ),
          ArithmeticSimplification.hideFactsAbout(TVar::eps::Nil) &
          implyRi & implyR(1) & andL('Llast) &

          (kis zip derbods).map(kb => ArithmeticSimplification.hideFactsAbout(kis.filter(_!=kb._1)) & odetac(kb._2)).reduceRight(
            (p1,p2) =>
              choiceb('Rlast) & andR('Rlast) <(p1,p2)
          )
//          (choiceb(pos) & andR(pos) <(odetac, skip)) //*(odes.length-1)
        )
      // todo: would be really nice if we can just pattern match on "everything with an underlyingControlled, including Controlled itself"
      case Controlled(initopt, modes, u) =>
        composeb(pos) &
          abstractionb(pos) & SaturateTactic(allR(pos)) &
          loop(inv)(pos) <(
            cutR(Equal(tVar,Number(0)))(pos) <(
              id,
              implyR(pos) & exhaustiveEqL2R('Llast) & SimplifierV3.simplify(pos) & closeT
            ),
            //Note: for these next two branches, the "loop" branch is moved to the first succedent position
            ArithmeticSimplification.hideFactsAbout(kis) &
              hideL('Llast) & implyRiLast & implyRiLast & (allL('Llast) * cvars.length) & implyL('Llast) <(
              andR('Rlast) <(prop, SaturateTactic(hideL('L)) & QE),
              andL(-1) & implyL('Llast) <( id, QE) //todo: this last QE can be done more manually
            ),
            implyRi & implyR(1) & andL('Llast) &
              composeb('Rlast) & abstractionb('Rlast) & SaturateTactic(allR('Rlast)) &

              (kis zip derbods).map(kb => ArithmeticSimplification.hideFactsAbout(kis.filter(_!=kb._1)) & composeb('Rlast) & abstractionb('Rlast) & odetac(kb._2)).reduceRight(
                (p1,p2) =>
                  choiceb('Rlast) & andR('Rlast) <(p1,p2)
              ))
    }

    allR(pos) & implyR(pos) & // \forall eps (eps>0 ->
      allR(pos) & implyR(pos) & // \forall del (del>0 ->
      cutR(delw)(pos) <( //find W,
        QE,
        implyR(pos) & existsL('Llast) & andL('Llast) & //skolemize W
        // todo: can first try a "simpler" cut here that works (remove V<W conjunct)
        cutR(epsu)(pos) <(
          hideL('Llast) & QE, //hide \forall x ( ||x|| < del -> V < W)
          implyR(pos) & existsL('Llast) & andL('Llast) & //skolemize U
          cutR(derFml)(pos) < (
            hideL('Llast) & hideL(-(apos+4)) &  //hide earlier quantified cuts
              (andR(pos) <( QE , skip))*(derivatives.length-1) & QE // QE each V' < k condition separately
            ,
            implyR(pos) &
              (andL('Llast) * (derivatives.length-1)) &
              (existsL(-(apos+7))) * derivatives.length & //existsL shuffles assumption to the back
              cutR(kMin)(pos) < (
                // hide everything except k_i < 0 assumptions
                SaturateTactic(andL('L)) & ArithmeticSimplification.keepFactsAbout(kis) &
                  (1 to kis.length).reverse.map( i => hideL(-2*i) : BelleExpr).reduceRight( _ & _ ) & QE,
                implyR(pos) & existsL('Llast) &
                existsRmon(Tchoice)(pos) <(
                  cohideOnlyL('Llast) & andL('Llast) & hideL('Llast) & QE,
                  andL('Llast) & andR(pos) <(
                    id,
                    (allR(pos) * cvars.length) & implyR(pos) & // strip forall x (d^2 < del^2 -> )
                    (allL(-(apos + 4)) * cvars.length) & implyL(-(apos + 4)) <( // discharge first cut on del
                      id,
                      // Don't need del anymore
                      ArithmeticSimplification.hideFactsAbout(del::Nil) &
                      hideL(-(apos+1)) & hideL(-(apos+1)) & hideL(-(apos+2)) & //hide eps > 0, W > 0 and U > 0 assumptions which aren't needed anymore from here
                      composeb(pos) & assignb(pos) & //t_=0 initially
                      conttac
                    )
                  )
                )
              )
          )
        )
      )

  }

    /** Check that ODE's domain includes points that are about to locally exit or enter it under the dynamics of the ODE
    *
    * @note for closed domains, this is automatically true
    * @note returns an option with string and counterexample if it manages to find one
    * @param sys the ODE under consideration
    * @param dom the "global" domain of points to be considered
    */
  def odeActive(sys: ODESystem, dom: Formula): Option[(String, Map[NamedSymbol, Expression])] = {

    val odels = DifferentialProduct.listify(sys.ode).map {
      case AtomicODE(x, e) => (x, e)
      case _ => throw new IllegalArgumentException("ODE sanity checks can only be used on concrete ODEs.")
    }
    val oder = odels.map(xe => AtomicODE(xe._1, Neg(xe._2))).reduceRight(DifferentialProduct.apply)

    val (normalized, _) = try {
      SimplifierV3.semiAlgNormalize(sys.constraint)
    } catch {
      case ex: IllegalArgumentException =>
        throw new IllegalArgumentException("Unable to normalize domain to semi-algebraic format: " + sys.constraint, ex)
    }

    //Points about to enter ODE domain under ODE dynamics
    val fwdfml = ODEInvariance.fStar(ODESystem(sys.ode, True), normalized)._1
    //Points about to leave ODE domain under ODE dynamics
    val bwdfml = ODEInvariance.fStar(ODESystem(oder, True), normalized)._1

    val checkfwd = Imply(And(dom, fwdfml), sys.constraint)
    val prfwd = findCounterExample(checkfwd)

    if (prfwd.nonEmpty)
      return Some("Unable to enter ODE " + sys, prfwd.get)

    val checkbwd = Imply(And(dom, bwdfml), sys.constraint)
    val prbwd = findCounterExample(checkbwd)

    if (prbwd.nonEmpty)
      return Some("Unable to leave ODE " + sys, prbwd.get)

    None
  }

  /** Check that all states can locally evolve under at least one ODE
    *
    * @param odes ODEs in the family
    * @param dom  the expected domain of the overall system (defaults: the entire state space)
    */
  def stateSwitchActive(odes: List[ODESystem], dom: Formula = True): Option[(String, Map[NamedSymbol, Expression])] = {

    val lp = odes.map(
      sys => {
        val (normalized, _) = try {
          SimplifierV3.semiAlgNormalize(sys.constraint)
        } catch {
          case ex: IllegalArgumentException =>
            throw new IllegalArgumentException("Unable to normalize domain to semi-algebraic format: " + sys.constraint, ex)
        }
        ODEInvariance.fStar(ODESystem(sys.ode, True), normalized)._1
      }
    ).reduceRight(Or)

    val check = Imply(dom, lp)
    val pr = findCounterExample(check)

    if (pr.nonEmpty)
      return Some("System cannot evolve forwards", pr.get)

    None
  }

  /** General time dependent switching model
    * Each ODE is indexed 0,...,n-1
    * Each index i can be associated with an (optional) positive time bound t <= T_i for the maximum dwell time
    * Each pair (i,j) can be associated with a positive time tau_{ij} <= t for minimum dwell time before transition
    *
    * @param odes        ODEs in the family with their time bounds
    * @param transitions for each ODE i, an associated list of pairs (j,tau_{ij})
    * @return a hybrid program modeling time-dependent switching between the ODEs
    */
  def timeSwitch(odes: List[(ODESystem, Option[Term])], transitions: List[List[(Int, Term)]],
                 s: Variable = "s_".asVariable, u: Variable = "u_".asVariable, topt: Option[BaseVariable] = None): Program = {

    if (odes.isEmpty)
      throw new IllegalArgumentException("Time-dependent switching requires at least 1 ODE")

    if (odes.length != transitions.length)
      throw new IllegalArgumentException("ODEs and transitions map must have same length")

    val todespre = odes.map(
      (odet) =>
        odet._2 match {
          case None =>
            ODESystem(DifferentialProduct(AtomicODE(DifferentialSymbol(s), Number(1)), odet._1.ode), odet._1.constraint)
          case Some(tt) =>
            ODESystem(DifferentialProduct(AtomicODE(DifferentialSymbol(s), Number(1)), odet._1.ode),
              And(odet._1.constraint, LessEqual(s, tt)))
        }
    )

    val todes: List[Program] = topt match {
      case None => todespre
      case Some(t) =>
        todespre.map(ode =>
          ODESystem(DifferentialProduct(AtomicODE(DifferentialSymbol(t), Number(1)), ode.ode), ode.constraint))
    }

    val resetclk = Assign(s, Number(0))

    //Setting up a choice for each ODE
    // \bigcup_i { ?u=i; ODE_i }
    val plant =
    todes.zipWithIndex.map(todei => Compose(Test(Equal(u, Number(todei._2))), todei._1)).reduceLeft(Choice)

    val ctrl = transitions.zipWithIndex.map(
      it => {
        val (trans, ind) = it
        val transprog =
          trans.map(ij => {
            if (ij._1 < 0 || ij._1 >= odes.length)
              throw new IllegalArgumentException("Unexpected transition to unknown ODE index: " + ij._1)
            if (ij._1 == ind)
              throw new IllegalArgumentException("Self transition not allowed for ODE index: " + ij._1)
            Compose(Test(GreaterEqual(s, ij._2)), Assign(u, Number(ij._1)))
          })
        Compose(
          Test(Equal(u, Number(ind))),
          Choice(
            Compose(transprog.reduceLeft(Choice), resetclk), // Either follow one of the transitions and reset the clock
            Assign(u, Number(ind)) //Or continue running current ODE without switching
          )
        )
      }).reduceLeft(Choice)

    val body = Compose(ctrl, plant)

    // Initialize the system in any ODE
    val init = Compose(resetclk, (0 to transitions.length - 1).map(i => Assign(u, Number(i))).reduceLeft(Choice))

    //    println(ctrl)
    //    println(plant)

    Compose(init, Loop(body))
  }


  /** Standard slow switching model
    * Each ODE is indexed 0,...,n-1
    *
    * @param odes  ODEs in the family
    * @param dwell the (global) minimum dwell time
    * @return a hybrid program modeling slow switching between the ODEs
    */
  def slowSwitch(odes: List[ODESystem], dwell: Term,
                 s: Variable = "s_".asVariable, u: Variable = "u_".asVariable, topt: Option[BaseVariable] = None): Program = {

    val transitions = (0 to odes.length-1).map( i =>
      (0 to odes.length-1).filterNot( j => j == i).map( j =>
        (j, dwell)
      ).toList
    ).toList

    timeSwitch(odes.map(ode => (ode,None)),transitions,s,u,topt)
  }

  /** Stability of the origin for a given hybrid program
    * \forall eps > 0 \exists del > 0
    * \forall x ( ||x|| < del -> [ a ] ||x|| < eps )
    *
    * @param prog    the hybrid program a to specify stability
    * @param varsopt Optional list of variables to quantify and perturb.
    *                By default None will pick non-differential freeVars(a) \cap boundVars(a)
    * @param restr   an optional additional restriction on the initial perturbation
    *
    *                With the additional options, we can write down slightly more nuanced specifications
    *
    *                \forall eps > 0 \exists del > 0
    *                \forall x ( ||x|| < del -> [ a ] ||x|| < eps )
    */
  def stableOrigin(prog: Program, varsopt: Option[List[(Variable,Term)]] = None, restr: Option[Formula] = None): Formula = {

    val bv = StaticSemantics.boundVars(prog).intersect(StaticSemantics.freeVars(prog)).toSet.filterNot(StaticSemantics.isDifferential(_)).map(_.asInstanceOf[Variable])

    val av = StaticSemantics.symbols(prog)

    // Fixed names for now
    val eps = Variable("eps")
    val del = Variable("del")
    if (av.contains(eps) || av.contains(del))
      throw new IllegalArgumentException("Hybrid program must not mention variables eps or del for stability specification")

    val vars: List[Variable] = varsopt match {
      case None => bv.toList
      case Some(vts) => vts.map( vt =>vt._1)
    }

    val normsq = varsopt match {
      case None =>
        vars.map(e => Power(e, Number(2))).reduceLeft(Plus) // ||x||^2
      case Some(vts) =>
        vts.map(ee => Power(Minus(ee._1,ee._2),Number(2))).reduceLeft(Plus)
    }

    val init = restr match {
      case None => Less(normsq, Power(del, Number(2)))
      case Some(f) => And(f, Less(normsq, Power(del, Number(2)))) // Apply a restriction if needed
    }

    val body = Imply(init,
      Box(prog, Less(normsq, Power(eps, Number(2))))
    )
    val qbody = vars.foldRight(body: Formula)((v, f) => Forall(v :: Nil, f))

    Forall(eps :: Nil, Imply(Greater(eps, Number(0)), // \forall eps > 0
      Exists(del :: Nil, And(Greater(del, Number(0)), // \exists del > 0
        qbody
      ))))
  }

  private lazy val edswap = remember(" f_() < del^2 -> del < eps -> del > 0 -> f_() < eps^2".asFormula, QE, namespace)

  /** Prove stability specification at the given top-level position using the given Lyapunov function V
    * V must satisfy the "basic" properties:
    * V(0) = 0, V > 0 away from the origin
    *
    * Additionally, V must be a "throughout" invariant of the hybrid program
    *
    * @param lyap    the (common) Lyapunov function
    * @param prog    ?
    * @param varsopt ?
    * @param restr   ?
    * @return stability tactic
    */
  def proveStable(lyap: Term, prog: Program, varsopt: Option[List[Variable]] = None, restr: Option[Formula] = None): DependentPositionTactic = anon((pos: Position, seq: Sequent) => {
    if (!(pos.isTopLevel && pos.isSucc))
      throw new IllFormedTacticApplicationException("proveStable: position " + pos + " must point to a top-level succedent position")

    //Note: we might attempt to decipher a stability specification instead
    //    val fml = seq.sub(pos) match {
    //      case Some(f) if f.isInstanceOf[Formula] => f.asInstanceOf[Formula]
    //      case _ => throw new IllFormedTacticApplicationException("Position " + pos + " does not point to a valid position in sequent " + seq.prettyString)
    //    }

    val bv = StaticSemantics.boundVars(prog).intersect(StaticSemantics.freeVars(prog)).toSet.filterNot(StaticSemantics.isDifferential(_)).map(_.asInstanceOf[Variable])

    val eps = Variable("eps")
    val del = Variable("del")
    val epssq = Power(eps, Number(2))
    val delsq = Power(del, Number(2))

    val w = Variable("w_")

    val vars: List[Variable] = varsopt.getOrElse(bv.toList) //TODO
    val normsq = vars.map(e => Power(e, Number(2))).reduceLeft(Plus) // ||x||^2

    val init = restr match {
      case None => Less(normsq, Power(del, Number(2)))
      case Some(f) => And(f, Less(normsq, Power(del, Number(2)))) // Apply a restriction if needed
    }

    // w serves as an lower bound on V for ||x||=eps
    val epsbound =
      vars.foldRight(Imply(Equal(normsq, epssq), GreaterEqual(lyap, w)): Formula)((v, f) => Forall(v :: Nil, f))
    val epsw = Exists(w :: Nil,
      And(Greater(w, Number(0)),
        epsbound
      ))
    // w serves as an upper bound on V for ||x||<del
    val delw = And(And(Greater(del, Number(0)), Less(del, eps)),
      vars.foldRight(Imply(Less(normsq, delsq), Less(lyap, w)): Formula)((v, f) => Forall(v :: Nil, f))
    )

    val apos = seq.ante.length

    // The relevant tactic for a single ODE
    // Assumes antecedent has the form:
    // assums, \forall x ( |x|=eps -> lyap >= w), lyap < w, |x| < eps
    val odetac =
    dC(Less(lyap, w))(pos) < (
      DifferentialTactics.dCClosure(pos) < (
        hideL(-(apos + 1)) & QE,
        cohideOnlyL(-(apos + 1)) &
          DifferentialTactics.DconstV(pos) &
          DifferentialTactics.diffWeakenG(pos) & implyR(1) &
          andL(-1) & (allL(-1) * vars.length) & QE // can be done by subst
      ),
      hideL(-(apos + 1)) & hideL(-(apos + 2)) & dI('full)(pos)
    )

    // The relevant tactic for a switched system
    val inv = And(Less(lyap, w), Less(normsq, epssq))
    val looptac = loop(inv)(pos) < (
      prop,
      prop,
      implyRi & implyR(pos) & andL('Llast) & chase(pos) &
        SaturateTactic(andR(pos) < (skip, odetac)) & odetac
    )

    val conttac = prog match {
      case ODESystem(_, _) => odetac
      case Loop(_) => looptac
      case _ => skip
    }

    allR(pos) & implyR(pos) &
      cutR(epsw)(pos) < (
        QE,
        implyR(pos) & existsL('Llast) & andL('Llast) &
          existsRmon(delw)(pos) < (
            hideL('Llast) & QE,
            // Get rid of some unused assumptions now
            hideL(-(apos + 1)) & hideL(-(apos + 1)) & andL(-(apos + 2)) & andL(-(apos + 2)) &
              andR(pos) < (
                id,
                (allR(pos) * vars.length) & implyR(pos) &
                  (allL(-(apos + 2)) * vars.length) & implyL(-(apos + 2)) < (
                  id,
                  cutR(Less(normsq, epssq))(pos) < (
                    (implyRi()(AntePos(apos + 2), pos.checkSucc) * 3) & cohideR(pos) & byUS(edswap),
                    (hideL(-(apos + 3)) * 3) & implyR(pos) &
                      conttac
                  )
                )
              )
          )
      )

  })



}