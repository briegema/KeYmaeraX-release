/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
package edu.cmu.cs.ls.keymaerax.core

import scala.collection.immutable
import edu.cmu.cs.ls.keymaerax.btactics.{DerivedRuleInfo, RandomFormula}
import edu.cmu.cs.ls.keymaerax.parser.{KeYmaeraXPrettyPrinter, SystemTestBase}
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.tags.{SummaryTest, USubstTest, UsualTest}
import edu.cmu.cs.ls.keymaerax.tools.KeYmaera
import org.scalatest._
import testHelper.KeYmaeraXTestTags
import testHelper.CustomAssertions.withSafeClue
import testHelper.KeYmaeraXTestTags.{AdvocatusTest, CoverageTest}
import testHelper.CustomAssertions._

import scala.collection.immutable.List
import scala.collection.immutable.Seq
import scala.collection.immutable.IndexedSeq
import scala.language.postfixOps

/**
  * Uniform substitution clash test dummies.
  *
  * @author Andre Platzer
 * @author smitsch
 */
@SummaryTest
@UsualTest
@USubstTest
class USubstTests extends SystemTestBase {
  val randomTrials = 50
  val randomComplexity = 20
  val rand = new RandomFormula()

  /** Test whether `operation(data)` is either a no-op returning `data` or throws an exception of type `E`. */
  def throwOrNoOp[In,Out,E:Manifest](operation: In => Out, data: In) = {
    var done = false
    try {
      // noop
      done = (operation(data) == data)
    }
    catch {
      case ignore : Throwable => done = false
    }
    if (!done) a [E] should be thrownBy {
      operation(data)
    }
  }


  //@note former core.UniformSubstitutionRule used here merely for the tests to continue to work even if they are less helpful
  @deprecated("Use Provable(USubst) rule instead")
  private def UniformSubstitutionRule(subst: USubst, origin: Sequent) : Sequent => immutable.List[Sequent] = conclusion =>
      try {
        //log("---- " + subst + "\n    " + origin + "\n--> " + subst(origin) + (if (subst(origin) == conclusion) "\n==  " else "\n!=  ") + conclusion)
        if (subst(origin) == conclusion) immutable.List(origin)
        else throw new InapplicableRuleException(this + "\non premise   " + origin + "\nresulted in  " + subst(origin) + "\nbut expected " + conclusion, null, conclusion)
        /*("From\n  " + origin + "\nuniform substitution\n  " + subst +
          "\ndid not conclude the intended\n  " + conclusion + "\nbut instead\n  " + subst(origin))*/
      } catch { case exc: SubstitutionClashException => throw exc.inContext(this + "\non premise   " + origin + "\nresulted in  " + "clash " + exc.clashes + "\nbut expected " + conclusion) }


  val x = Variable("x", None, Real)
  val z = Variable("z", None, Real)
  val p0 = PredOf(Function("p", None, Unit, Bool), Nothing)
  val p1 = Function("p", None, Real, Bool)
  val p1_ = Function("p_", None, Real, Bool)
  val pn = Function("p", None, Real, Bool)
  val pn_ = Function("p_", None, Real, Bool)
  val qn = Function("q", None, Real, Bool)
  val qn_ = Function("q_", None, Real, Bool)
  val ap = ProgramConst("a")
  val ap_ = ProgramConst("a_")
  //val f1 = Function("f", None, Real, Real)
  val f1_ = Function("f_", None, Real, Real)
  //val g1 = Function("g", None, Real, Real)
  val g1_ = Function("g_", None, Real, Real)

  val ctx  = Function("ctx_", None, Bool, Bool)
  val ctxt = Function("ctx_", None, Real, Real)
  val ctxf = Function("ctx_", None, Real, Bool)

  "Uniform substitution" should "substitute simple formula p(x) <-> ! ! p(- - x)" in {
    val p = Function("p", None, Real, Bool)
    val x = Variable("x", None, Real)
    // p(x) <-> ! ! p(- - x)
    val prem = Equiv(PredOf(p, x), Not(Not(PredOf(p, Neg(Neg(x))))))
    val s = USubst(Seq(SubstitutionPair(PredOf(p, DotTerm()), GreaterEqual(Power(DotTerm(), Number(5)), Number(0)))))
    s(prem) should be ("x^5>=0 <-> !(!((-(-x))^5>=0))".asFormula)
  }

  it should "substitute with dot projection" ignore {
    val p = Function("p", None, Tuple(Real, Real), Bool)
    val x = Variable("x", None, Real)
    val y = Variable("y", None, Real)
    val dot = DotTerm(Tuple(Real, Real))
    // p(x,y) <-> ! ! p(- - x, - -y)
    val prem = Equiv(PredOf(p, Pair(x, y)), Not(Not(PredOf(p, Pair(Neg(Neg(x)), Neg(Neg(y)))))))
    //@todo fst/snd not yet available
    val s = USubst(Seq(SubstitutionPair(PredOf(p, dot), GreaterEqual(Power("fst(.(.,.))".asTerm, "snd(.(.,.))".asTerm), Number(0)))))
    s(prem) should be ("x^y>=0 <-> !(!((-(-x))^(-(-(y)))>=0))".asFormula)
  }

  it should "substitute with more complicated dot projection" ignore {
    val p = Function("p", None, Tuple(Real, Tuple(Real, Real)), Bool)
    val x = Variable("x", None, Real)
    val y = Variable("y", None, Real)
    val z = Variable("z", None, Real)
    val f = Function("f", None, Tuple(Real, Real), Real)
    val dot = DotTerm(Tuple(Real, Tuple(Real, Real)))
    // p(x,y,z) <-> ! ! p(- - x, - -y,z)
    val prem = Equiv(PredOf(p, Pair(x, Pair(y, z))), Not(Not(PredOf(p, Pair(Neg(Neg(x)), Pair(Neg(Neg(y)), z))))))
    //@todo fst/snd not yet available
    val s = USubst(Seq(SubstitutionPair(PredOf(p, dot),
      GreaterEqual(Power("fst(.(.,.))".asTerm, FuncOf(f, Pair("fst(snd(.(.,(.,.))))".asTerm, "snd(snd(.(.,(.,.))))".asTerm))), Number(0)))))
    s(prem) should be ("x^f(y,z)>=0 <-> !(!((-(-x))^f(-(-(y)),z)>=0))".asFormula)
  }

  it should "substitute unary predicate with binary predicate" in {
    val s = USubst(
      SubstitutionPair("p(.)".asFormula, "r(.,g())".asFormula) ::
      SubstitutionPair("f()".asTerm, "2*a^2".asTerm) :: Nil)
    val prem = "\\forall x p(x) -> p(f())".asFormula
    s(prem) shouldBe "\\forall x r(x,g()) -> r(2*a^2,g())".asFormula
  }

  it should "substitute simple sequent p(x) <-> ! ! p(- - x)" in {
    val p = Function("p", None, Real, Bool)
    val x = Variable("x", None, Real)
    // p(x) <-> ! ! p(- - x)
    val prem = Equiv(PredOf(p, x), Not(Not(PredOf(p, Neg(Neg(x))))))
    val s = USubst(Seq(SubstitutionPair(PredOf(p, DotTerm()), GreaterEqual(Power(DotTerm(), Number(5)), Number(0)))))
    val conc = "x^5>=0 <-> !(!((-(-x))^5>=0))".asFormula
    Provable.startProof(prem)(s).conclusion shouldBe Sequent(IndexedSeq(), IndexedSeq(conc))
  }

  it should "old substitute simple sequent p(x) <-> ! ! p(- - x)" in {
    val p = Function("p", None, Real, Bool)
    val x = Variable("x", None, Real)
    // p(x) <-> ! ! p(- - x)
    val prem = Equiv(PredOf(p, x), Not(Not(PredOf(p, Neg(Neg(x))))))
    val s = USubst(Seq(SubstitutionPair(PredOf(p, DotTerm()), GreaterEqual(Power(DotTerm(), Number(5)), Number(0)))))
    val conc = "x^5>=0 <-> !(!((-(-x))^5>=0))".asFormula
    UniformSubstitutionRule(s,
      Sequent(IndexedSeq(), IndexedSeq(prem)))(
        Sequent(IndexedSeq(), IndexedSeq(conc))) shouldBe List(Sequent(IndexedSeq(), IndexedSeq(prem)))
  }

  it should "substitute simple formula [a]p(x) <-> [a](p(x)&true)" in {
    val p = Function("p", None, Real, Bool)
    val x = Variable("x", None, Real)
    val a = ProgramConst("a")
    // [a]p(x) <-> [a](p(x)&true)
    val prem = Equiv(Box(a, PredOf(p, x)), Box(a, And(PredOf(p, x), True)))
    val s = USubst(Seq(SubstitutionPair(PredOf(p, DotTerm()), GreaterEqual(DotTerm(), Number(2))),
      SubstitutionPair(a, ODESystem(AtomicODE(DifferentialSymbol(x), Number(5)), True))))
    s(prem) should be ("[{x'=5}]x>=2 <-> [{x'=5}](x>=2&true)".asFormula)
  }

  it should "substitute simple sequent [a]p(x) <-> [a](p(x)&true)" in {
    val p = Function("p", None, Real, Bool)
    val x = Variable("x", None, Real)
    val a = ProgramConst("a")
    // [a]p(x) <-> [a](p(x)&true)
    val prem = Equiv(Box(a, PredOf(p, x)), Box(a, And(PredOf(p, x), True)))
    val s = USubst(Seq(SubstitutionPair(PredOf(p, DotTerm()), GreaterEqual(DotTerm(), Number(2))),
      SubstitutionPair(a, ODESystem(AtomicODE(DifferentialSymbol(x), Number(5)), True))))
    val conc = "[{x'=5}]x>=2 <-> [{x'=5}](x>=2&true)".asFormula
    Provable.startProof(prem)(s).conclusion shouldBe Sequent(IndexedSeq(), IndexedSeq(conc))
  }
  it should "old substitute simple sequent [a]p(x) <-> [a](p(x)&true)" in {
    val p = Function("p", None, Real, Bool)
    val x = Variable("x", None, Real)
    val a = ProgramConst("a")
    // [a]p(x) <-> [a](p(x)&true)
    val prem = Equiv(Box(a, PredOf(p, x)), Box(a, And(PredOf(p, x), True)))
    val s = USubst(Seq(SubstitutionPair(PredOf(p, DotTerm()), GreaterEqual(DotTerm(), Number(2))),
      SubstitutionPair(a, ODESystem(AtomicODE(DifferentialSymbol(x), Number(5)), True))))
    val conc = "[{x'=5}]x>=2 <-> [{x'=5}](x>=2&true)".asFormula
    UniformSubstitutionRule(s,
      Sequent(IndexedSeq(), IndexedSeq(prem)))(
      Sequent(IndexedSeq(), IndexedSeq(conc))) shouldBe List(Sequent(IndexedSeq(), IndexedSeq(prem)))
  }

  it should "old clash when using [:=] for a substitution with a free occurrence of a bound variable" taggedAs(KeYmaeraXTestTags.USubstTest,KeYmaeraXTestTags.CheckinTest) in {
    val fn = FuncOf(Function("f", None, Unit, Real), Nothing)
    val prem = Equiv(
      Box("x:=f();".asProgram, PredOf(p1, "x".asTerm)),
      PredOf(p1, fn)) // axioms.axiom("[:=])
    val conc = "[x:=x+1;]x!=x <-> x+1!=x".asFormula
    val s = USubst(Seq(SubstitutionPair(PredOf(p1, DotTerm()), NotEqual(DotTerm(), "x".asTerm)),
      SubstitutionPair(fn, "x+1".asTerm)))
    a [SubstitutionClashException] should be thrownBy UniformSubstitutionRule(s,
      Sequent(IndexedSeq(), IndexedSeq(prem)))(
      Sequent(IndexedSeq(), IndexedSeq(conc)))
  }

  it should "old clash when using [:=] for a substitution with a free occurrence of a bound variable for constants" taggedAs(KeYmaeraXTestTags.USubstTest,KeYmaeraXTestTags.CheckinTest) in {
    val fn = FuncOf(Function("f", None, Unit, Real), Nothing)
    val prem = Equiv(
      Box("x:=f();".asProgram, PredOf(p1, "x".asTerm)),
      PredOf(p1, fn)) // axioms.axiom("[:=])
    val conc = "[x:=0;]x=x <-> 0=x".asFormula
    val s = USubst(Seq(SubstitutionPair(PredOf(p1, DotTerm()), Equal(DotTerm(), "x".asTerm)),
      SubstitutionPair(fn, "0".asTerm)))
    a [SubstitutionClashException] should be thrownBy UniformSubstitutionRule(s,
      Sequent(IndexedSeq(), IndexedSeq(prem)))(
      Sequent(IndexedSeq(), IndexedSeq(conc)))
  }

  it should "old handle nontrivial binding structures" taggedAs KeYmaeraXTestTags.USubstTest in {
    val fn = FuncOf(Function("f", None, Unit, Real), Nothing)
    val prem = Equiv(
      Box("x:=f();".asProgram, PredOf(p1, "x".asTerm)),
      PredOf(p1, fn)) // axioms.axiom("[:=])
    val conc = "[x:=x^2;][{y:=y+1;++{z:=x+z;}*}; z:=x+y*z;]y>x <-> [{y:=y+1;++{z:=x^2+z;}*}; z:=x^2+y*z;]y>x^2".asFormula

    val y = Variable("y", None, Real)
    val z = Variable("z", None, Real)
    val s = USubst(Seq(
      // [{y:=y+1++{z:=.+z}*}; z:=.+y*z]y>.
      SubstitutionPair(PredOf(p1, DotTerm()), Box(
        Compose(
          Choice(
            Assign(y, Plus(y, Number(1))),
            Loop(Assign(z, Plus(DotTerm(), z)))
          ),
          Assign(z, Plus(DotTerm(), Times(y, z)))),
        Greater(y, DotTerm()))),
      SubstitutionPair(fn, "x^2".asTerm)))
    UniformSubstitutionRule(s, Sequent(IndexedSeq(), IndexedSeq(prem)))(Sequent(IndexedSeq(), IndexedSeq(conc))) should be (List(Sequent(IndexedSeq(), IndexedSeq(prem))))
  }

  it should "clash when using vacuous all quantifier forall x for a postcondition x>=0 with a free occurrence of the bound variable" taggedAs(KeYmaeraXTestTags.USubstTest,KeYmaeraXTestTags.SummaryTest) in {
    val x = Variable("x_",None,Real)
    val fml = GreaterEqual(x, Number(0))
    val prem = Provable.axioms("vacuous all quantifier")
    val conc = Forall(Seq(x), fml)
    val s = USubst(Seq(SubstitutionPair(p0, fml)))
    //a [SubstitutionClashException] should be thrownBy
    val e = intercept[ProverException] {
      prem(s)
    }
    (e.isInstanceOf[SubstitutionClashException] || e.isInstanceOf[InapplicableRuleException]) shouldBe true
  }
  it should "old clash when using vacuous all quantifier forall x for a postcondition x>=0 with a free occurrence of the bound variable" taggedAs(KeYmaeraXTestTags.USubstTest,KeYmaeraXTestTags.SummaryTest) in {
    val fml = GreaterEqual(x, Number(0))
    val prem = Provable.axiom("vacuous all quantifier")
    val conc = Forall(Seq(x), fml)
    val s = USubst(Seq(SubstitutionPair(p0, fml)))
    //a [SubstitutionClashException] should be thrownBy
    val e = intercept[ProverException] {
      UniformSubstitutionRule(s,
        Sequent(IndexedSeq(), IndexedSeq(prem)))(
        Sequent(IndexedSeq(), IndexedSeq(conc)))
    }
    (e.isInstanceOf[SubstitutionClashException] || e.isInstanceOf[InapplicableRuleException]) shouldBe true
  }

  it should "old clash when using \"c()' derive constant fn\" for a substitution with free occurrences" taggedAs KeYmaeraXTestTags.USubstTest in {
    val aC = FuncOf(Function("c", None, Unit, Real), Nothing)
    val prem = "(c())'=0".asFormula // axioms.axiom("c()' derive constant fn")
    val conc = "(x)'=0".asFormula
    val s = USubst(Seq(SubstitutionPair(aC, "x".asTerm)))
    a [SubstitutionClashException] should be thrownBy UniformSubstitutionRule(s,
      Sequent(IndexedSeq(), IndexedSeq(prem)))(
      Sequent(IndexedSeq(), IndexedSeq(conc)))
  }

  it should "old clash when using \"c()' derive constant fn\" for a substitution with free differential occurrences" taggedAs KeYmaeraXTestTags.USubstTest in {
    val aC = FuncOf(Function("c", None, Unit, Real), Nothing)
    val prem = "(c())'=0".asFormula // axioms.axiom("c()' derive constant fn")
    val conc = "(x')'=0".asFormula
    val s = USubst(Seq(SubstitutionPair(aC, "x'".asTerm)))
    a [SubstitutionClashException] should be thrownBy UniformSubstitutionRule(s,
      Sequent(IndexedSeq(), IndexedSeq(prem)))(
      Sequent(IndexedSeq(), IndexedSeq(conc)))
  }

  it should "refuse to accept ill-kinded substitutions outright" in {
    a[CoreException] should be thrownBy SubstitutionPair(FuncOf(Function("a", None, Unit, Real), Nothing), Greater(Variable("x"), Number(5)))
    a[CoreException] should be thrownBy SubstitutionPair(FuncOf(Function("a", None, Real, Real), DotTerm()), Greater(Variable("x"), Number(5)))
    a[CoreException] should be thrownBy SubstitutionPair(FuncOf(Function("a", None, Unit, Real), Nothing), ProgramConst("c"))
    a[CoreException] should be thrownBy SubstitutionPair(FuncOf(Function("a", None, Real, Real), DotTerm()), ProgramConst("c"))
    a[CoreException] should be thrownBy SubstitutionPair(PredOf(Function("p", None, Unit, Bool), Nothing), Number(5))
    a[CoreException] should be thrownBy SubstitutionPair(PredOf(Function("p", None, Real, Bool), DotTerm()), Number(5))
    a[CoreException] should be thrownBy SubstitutionPair(PredOf(Function("p", None, Unit, Bool), Nothing), ProgramConst("c"))
    a[CoreException] should be thrownBy SubstitutionPair(PredOf(Function("p", None, Real, Bool), DotTerm()), ProgramConst("c"))
    a[CoreException] should be thrownBy SubstitutionPair(ProgramConst("c"), FuncOf(Function("a", None, Unit, Real), Nothing))
    a[CoreException] should be thrownBy SubstitutionPair(ProgramConst("c"), Greater(Variable("x"), Number(5)))
  }

  it should "refuse to accept ill-shaped substitutions outright" in {
    a [CoreException] should be thrownBy SubstitutionPair(Number(7), Number(9))
    a [CoreException] should be thrownBy SubstitutionPair(Variable("x"), Number(9))
    a [CoreException] should be thrownBy SubstitutionPair(Plus(Variable("x"),Number(7)), Number(9))
    a [CoreException] should be thrownBy SubstitutionPair(Plus(Number(2),Number(7)), Number(9))
    a [CoreException] should be thrownBy SubstitutionPair(Plus(FuncOf(Function("a", None, Unit, Real), Nothing),FuncOf(Function("b", None, Unit, Real), Nothing)), Number(9))
    a [CoreException] should be thrownBy SubstitutionPair(And(Greater(Number(7),Number(2)), Less(Number(2),Number(1))), False)
    a [CoreException] should be thrownBy SubstitutionPair(AssignAny(Variable("x")), ProgramConst("c"))
    a [CoreException] should be thrownBy SubstitutionPair(AssignAny(Variable("x")), AssignAny(Variable("y")))
    a [CoreException] should be thrownBy SubstitutionPair(Assign(Variable("x"), Number(7)), Assign(Variable("y"), Number(7)))
    a [CoreException] should be thrownBy SubstitutionPair(Assign(Variable("x"), Number(7)), AssignAny(Variable("y")))
    a [CoreException] should be thrownBy SubstitutionPair(AtomicODE(DifferentialSymbol(Variable("x")), Number(7)), AssignAny(Variable("x")))
    a [CoreException] should be thrownBy SubstitutionPair(ODESystem(AtomicODE(DifferentialSymbol(Variable("x")), Number(7)), True), AssignAny(Variable("x")))
  }

  it should "refuse duplicate substitutions outright" in {
    val list1 = SubstitutionPair(FuncOf(Function("a", None, Real, Real), DotTerm()), Number(5)) ::
      SubstitutionPair(FuncOf(Function("a", None, Real, Real), DotTerm()), Number(22)) :: Nil
    a[CoreException] should be thrownBy USubst(list1)
    val list2 = SubstitutionPair(PredOf(Function("p", None, Unit, Bool), Nothing), Greater(Variable("x"), Number(5))) ::
      SubstitutionPair(PredOf(Function("p", None, Unit, Bool), Nothing), Less(Variable("z"), Number(99))) :: Nil
    a[CoreException] should be thrownBy USubst(list2)
    val list3 = SubstitutionPair(ProgramConst("c"), Assign(Variable("y"), Number(7))) ::
      SubstitutionPair(ProgramConst("c"), AssignAny(Variable("y"))) :: Nil
    a[CoreException] should be thrownBy USubst(list3)
  }

  it should "refuse ++ union that lead to duplicate substitutions" in {
    val list1 = (USubst(SubstitutionPair(FuncOf(Function("a", None, Real, Real), DotTerm()), Number(5))::Nil),
      USubst(SubstitutionPair(FuncOf(Function("a", None, Real, Real), DotTerm()), Number(22)) :: Nil))
    a[CoreException] should be thrownBy (list1._1 ++ list1._2)
    (list1._1 ++ list1._1) shouldBe list1._1
    (list1._2 ++ list1._2) shouldBe list1._2
    val list2 = (USubst(SubstitutionPair(PredOf(Function("p", None, Unit, Bool), Nothing), Greater(Variable("x"), Number(5))) :: Nil),
      USubst(SubstitutionPair(PredOf(Function("p", None, Unit, Bool), Nothing), Less(Variable("z"), Number(99))) :: Nil))
    a[CoreException] should be thrownBy (list2._1 ++ list2._2)
    (list2._1 ++ list2._1) shouldBe list2._1
    (list2._2 ++ list2._2) shouldBe list2._2
    val list3 = (USubst(SubstitutionPair(ProgramConst("c"), Assign(Variable("y"), Number(7))) :: Nil),
      USubst(SubstitutionPair(ProgramConst("c"), AssignAny(Variable("y"))) :: Nil))
    a[CoreException] should be thrownBy (list3._1 ++ list3._2)
    (list3._1 ++ list3._1) shouldBe list3._1
    (list3._2 ++ list3._2) shouldBe list3._2
  }

  // uniform substitution of rules

  /** Program produced in
    * 12th run of 50 random trials,
    * generated with 20 random complexity
    * from seed -3583806640264782477L
    */
  "Uniform substitution of rules" should "instantiate crazy program in [] monotone" taggedAs KeYmaeraXTestTags.USubstTest ignore {
      val prem1 = "(-z1)^2>=z4".asFormula
      val prem2 = "z4<=z1^2".asFormula
      val prog = "{{z6'=0^2,z3'=0+(0+-50)&<?\\forall z6 [{{z4:=*;{{z7'=0,z6'=0&[{z2:=z2;{z2:=*;++?true;}}{{?true;z6:=*;++?true;}++{?true;?true;++{?true;}*}++{?true;}*++{?true;}*}]\\forall z3 (\\forall z4 true)'}z2:=z5';}*}{z5:=0;{{{z1:=z1;++?true;++{?true;?true;}*}{{z7'=0,z2'=0&\\forall z5 true}}*?true;++{{{?true;++?true;}*{{?true;}*++?true;}}{{?true;++z5:=0;}++{{?true;}*}*}}*}++?true;}?true;}*++{z6:=-30+0;++{{z5:=(0+z3)'-0;++z5:=(0+0-0/0)^0+z2+((gg()^4)'+ff(0));}++{{{{?true;?true;}*{z2'=28&true&true}++?true;}++z3:=z2;}++?true;}*}?\\forall z6 \\forall z4 true;}{{{{?true;}*}*++{z7'=0&z2*-97*(0)'>=(z4/(0-(0+0)))'}}*}*{?false;++{{{?true;}*++z7:=z7;}++z6:=*;}{?[{z1'=0&\\exists z1 <?true;>true}]\\exists z2 -97 < 0*0&<{{?true;}*}*++?true;++?true;++?true;>[{?true;++?true;}*]\\forall z2 <?true;>true;}*}}?<{{{{{{z3'=-12&\\exists z2 true}{?true;++?true;}*}*}*++z1:=-35;}?true;}*++?true;}++z6:=*;++?false;{{z2:=z2;++{{?true;?true;++{?true;}*}++?true;?true;++z3:=0;}*}{{{z5:=z5;}*++{?true;++?true;}{?true;}*}{{?true;}*{?true;++?true;}++?true;}}{z3:=*;++{z4:=0;}*{?true;?true;}{?true;++?true;}}++{{{{?true;}*}*++{?true;?true;++?true;}*}++{{?true;?true;}{?true;}*}*{{{?true;}*++{z6'=0&true}}++{?true;}*}}*}>\\forall z1 <{{{{{?true;}*++?true;++?true;}*}*{{?true;++?true;}*}*{{?true;}*}*z1:=*;}*++{{{{?true;?true;}*}*++z4:=*;}++{z6'=39&\\forall z3 <?true;>true|\\forall z4 [?true;]true}}?[{{?true;?true;}*}*][{?true;++?true;}*]\\exists z2 0>=0;}?(false&ff(z4) < (39-0*0)/-54)';>true;]0<=ff(z6);{{{{?[{?true;}*]\\exists z6 0=(55-z5)';++{z5:=z1';++{?true;}*?true;++?true;}{{{{{{?true;++?true;}++?true;}*++{z7'=0&[{?true;}*]\\forall z7 true}}++{?true;?true<->true;}*}{{{?true;?true;++{?true;}*}++{z7'=0,z5'=0,z2'=0&0=0}}?true;{z7'=0,z3'=0&true}{?true;++?true;}}*}*{z6:=z6;++{{z5:=*;++?[?true;]true;}{{?true;?true;}z1:=z1;}{?true;++?true;?true;}}?true;++{z2'=ff((0-0)^1)+25&z6'>z5'}}}z3:=z5';}++{{{{{{z4:=(93)';{?true;++?\\exists z6 true;}*}{{?true;++?true;?true;}++?true;}*{{{z3'=0&true}{z2'=0&true}}*}*}?true;}{{?true;++{?true;++z4:=*;{?true;++?true;?true;}}++z4:=z1/0;}++{{{?true;?true;}*z1:=*;{z7'=0&true}++z6:=0/0+0/0;}?true;}{{z4:=*;++z7:=*;}{z4:=z4;{?true;}*++{?true;?true;}*}}*}}{z7'=0&10-87<=0-z4-z1'}{?true;}*}{z3:=*;++{{{{?true;}*}*}*{{{?true;}*++{?true;}*}{?true;++{?true;}*}++{z2:=0;{?true;}*}*}}{{{{?true;++?true;}++z5:=0;}++?true;?true;++?true;}*++{{{?true;}*?true;?true;}{?true;++?true;}}z4:=0+0-0;}++{z3'=24&true}}{{z6'=0&(78^0)'>z7'}++{{{{?true;++?true;}{?true;}*++?true;}*}*{z6:=ff((0)')*(0*0)^0;++?true;++{?true;++?true;}*{?true;}*?true;?true;}}{{?true;}*++{{{z5:=0;}*{z4:=0;}*}?true;?0!=0;}{{{?true;?true;}?true;++{?true;++?true;}++{?true;}*}++{z3'=0,z1'=0&<?true;++?true;>(true|true)}}}}}*}{z1:=*;++?(gg()*(z4*(z3+z4)))^1/((ff(0-0+(0)'+(0+0)*(0*0))+0)/-17)^4!=(90)'-0;++{{z6'=0,z3'=0&[?true;++{{?true;?true;++?true;?true;}*}*++{?true;++{?true;++?true;}*}{{z2'=0-0&<?true;>true}}*][?true;]<{{?true;?true;}{?true;++?true;}}*++?[{?true;}*]qq();>(0/-13)^3 < z7}}*{{{{z7:=z7;}*++{{{?false;{?true;}*}z5:=0+0;}z1:=z2';}{?true;++{?true;++?true;}*++{?true;?true;}{?true;++?true;}}}{{{{z6'=0,z3'=8&true&true->true->true}}*}*++{{{{?true;}*}*{?true;?true;++{?true;}*}}?true;}*}}*++{{z5'=0,z3'=78&z2'>=0}}*{z7'=0&<?true;{z7'=0&[{?true;}*++{z4'=0,z3'=0&true}]true}>(((0*0=0-0<-><{?true;}*>\\forall z3 true)<->(gg()>=-86)')&(true|!<z4:=0;>0<=0))}}}++z4:=z2;}++z4:=*;++{?true;}*}*>(<{{{?true;?true;{{{{?true;}*}*{?true;}*}*{z4:=0*(0*0)'*(24^1)'+(0-(gg())'-z3');}*++?true;z4:=(93)';++z1:=z1';++{{z6'=0,z4'=0&[{?true;}*]true}{?true;?true;}*{{?true;}*}*}*{z5:=z5;++{?true;?true;++?true;}*++{{?true;}*}*{?true;?true;++{?true;}*}}}}{?true;++{{{{z6'=0-z5&ff(0)*(-10-z6)>=z5-35*z3}}*++?true;}++{{?[?true;]true|0>0;?true;z4:=z4;++{{z7:=z7;{?true;}*}*}*}{?true;{z1'=0&<?true;?true;>0!=0}}{?(true)';++?[?true;]true;}*}{{?true;?<?true;>true|0>0;++{z6:=*;{z4:=0;}*}?<?true;++?true;>0 < 0;}++z4:=0/(0+0)*-56^3;++{{?true;}*}*}}{z7:=ff((42)'+(0+0-0*0)');}*{?true;++z5:=z5;}++{z6:=ff((-74-0-(0*0+0))');{{{z4:=0;?true;}{{?true;}*}*}*++z6:=z6;}z2:=*;++{{{z2:=(z1)';}*}*}*}*}}*{z1:=z7;++{{?true;}*++{{{z7'=0,z1'=0&0-z5'-z2'>(z7)'}++?true;}++z4:=z4;{z7'=0,z2'=0&\\exists z5 z6'--11!=(0^2+(0-0))'/z1}}*}*}}*><{?true;{z3'=0,z2'=0&([{z6'=0,z2'=0&<{z2:=z2;++?true;++{?true;}*}*++{?true;?true;++?true;?true;}*{{?true;++?true;++?true;}++?true;}>(54-(z5+z5))^2=0}]\\exists z7 [?true;][{z1:=z1;}*++{?true;?true;}*++?true;?true;?true;]0+(z7-0*0)>z3*0*(0+0+gg()))'}{z5'=0&<{?ff((0-0)/(0+0))*((95)'+z1*0) < z6';++{{z6'=0,z1'=0&0^1+0>z7'}++z7:=z7;}?true;}*><?true;>(25/ff(0^3))'/((0-0)^4*(0*0+z3')*(9/(0*0)*73))+gg()>ff(0/z7)*(z6/(((0)'+(0+0))/z7+(0-0-0*0)'))}++{z1:=*;++?true;}{z4'=0&pp(((z3-50^1)^3)')}}*>0=z5-((58-50)'+z6)+0<-><{{?true;{{?true;}*++{{{{{{{?true;?true;++?true;?true;}++{?true;++?true;}++?true;++?true;}++{z4:=0;++?true;}{?true;?true;++?true;}}++?true;++{?true;++?true;++?true;}++{?true;}*++{?true;}*}*++?true;{{{?true;++?true;}*}*++z7:=*;}?true;}++?[{?true;{{?true;++?true;}++?true;?true;}}{?true;?true;}*?true;]<{z6:=0;++?true;?true;}{?true;}*{?true;++?true;}>0<=0;{{{{{?true;}*}*}*++{{?true;++?true;}++{?true;}*}*}*++z7:=z4*z5';++{?true;++?true;}*?<?true;>true;{?true;?true;++{?true;}*}}}++z3:=*;}++{z2:=z2';{{z7'=0&<{?true;?true;++z5:=z5;}++{?true;++?true;}++?true;++?true;>\\exists z3 <?true;?true;>0>0}}*++{{{{{?true;++?true;}{?true;++?true;}}*}*{?<{?true;}*>\\exists z5 true;++{z2'=87&true|true}++?true;}}*}*}++{z2'=0&<?true;>(93)'>-51}{{{z1'=0&<?true;>true}}*{{z1'=0&<z7:=0*0;>(\\forall z7 true&!true)}}*}{z4:=*;{{?true;++?true;}?true;?true;}z3:=*;++?true;}?true;z6:=z3*(0-0);{{{z5'=0&true}}*}*}}{{{z7'=0&true}}*++{z5:=z5;++{{z7'=0&ff(0*0)-(-15-z4)+gg()>0-58}++?true;}++z4:=z4;}{{{{{?true;}*{?true;?true;}?true;?true;}*++{?true;}*}z5:=2;{z6:=0;++z3:=9;}++z3:=*;}{{{z4'=(0+0)^0,z2'=0&true}z5:=*;}*++?true;{z1:=z1;++{z7:=0;++z1:=z1;}*{?true;++{?true;++?true;}++?true;?true;}}}}{{{z5:=*;++?true;++{?true;?true;++?true;?true;}++{?true;++?true;}?true;?true;}*}*++{{?true;++{{z5'=0/0&[?true;]true}?0=0;}?true;}{z6:=*;++{{?true;++?true;}z5:=0;++{?true;}*++?true;?true;}{?false;}*}}*}}*}*?true;>\\forall z6 (gg())'!=z4')}}*".asProgram
      val concLhs = Box(prog, prem1)
      val concRhs = Box(prog, prem2)
      val prgString = withSafeClue("Error printing crazy program") {
        KeYmaeraXPrettyPrinter.stringify(prog)
      }

      withSafeClue("Random precontext " + prgString + "\n\n") {
        println("Random precontext " + prog.prettyString)

        val q_ = Function("q_", None, Real, Bool)
        val s = USubst(Seq(
          SubstitutionPair(ap_, prog),
          SubstitutionPair(UnitPredicational("p_", AnyArg), prem1),
          SubstitutionPair(UnitPredicational("q_", AnyArg), prem2)
        ))
        val pr = DerivedRuleInfo("[] monotone").provable(s)
        pr.conclusion shouldBe Sequent(IndexedSeq(concLhs), IndexedSeq(concRhs))
        pr.subgoals should contain only Sequent(IndexedSeq(prem1), IndexedSeq(prem2))
      }
  }

  /**
    * Program produced in
	 38th run of 50 random trials,
	 generated with 20 random complexity
	 from seed -3583806640264782477L
    */
  it should "instantiate crazy program in <> congruence" taggedAs KeYmaeraXTestTags.USubstTest ignore {
      val prem1 = "(-z1)^2>=z4".asFormula
      val prem2 = "z4<=z1^2".asFormula
      val prem = Equiv(prem1, prem2)
      val prog = "{{{{?true;++{?\\forall z6 ([{z5'=21&[{?true;{?true;++?true;{?true;}*}}*]-40 < 0}]<{{{{z7'=0&true}{?true;++{z5'=0,z3'=0&\\forall z5 true}{?true;?true;}?true;?true;}}{{{z3'=0*0&true}++{?false;}*}++z1:=*;}{{{?true;++?true;}*}*++z1:=*;}}?true;?true;}*{{{z5:=z5;++?true;}++z7:=*;z6:=z6;}{{z6'=6&z5 < 0}}*++?true;}*>PP{qq()})';}*}z3:=z3;}{{?true;z3:=ff((gg()-ff(((0/(z2*z4)-(1-48))/(-28-66))')+(z5+z2')/(0*(gg()+(z4'-gg())-z7)/z1*z2'))/((z5'-z4)*gg())^5);++z2:=z2;?true;}z4:=*;++{{?true;}*++?true;}z5:=*;}}{{?true;{{{{z7'=0&true}}*++{{z1:=z3';++{{{z3:=z6;z2:=z2;}*?qq();}{z6:=*;}*}*}++{?true;z1:=*;}*++z4:=z4;?<{?<?true;>[?true;]true;{z4:=0;{?true;}*}{?true;?true;}{?true;}*}{{z1:=0;}*}*?true;++{{?true;++{{?true;}*}*}++?<z5:=0;><?true;>true;}{{{?true;++?true;}{z5'=0&true}}?true;}*>[{{z3'=0+0+(0+0)&[?true;?true;]\\forall z2 true}++?true;++?true;?true;?true;}{{?true;}*{?true;?true;}?true;}z2:=*;]<?true;><z7:=*;++z5:=z5;++?true;++?true;>z4'+gg()!=z1+(0-0);}{z4:=-50/z1^1;}*?true;}*}*}*++?true;}}z2:=(ff(-13-z5))';".asProgram
      val conc = Equiv(Diamond(prog, prem1), Diamond(prog, prem2))

      val prgString = withSafeClue("Error printing crazy program\n\n") {
        KeYmaeraXPrettyPrinter.stringify(prog)
      }

      withSafeClue("Random precontext " + prgString + "\n\n") {
        println("Random precontext " + prog.prettyString)

        val q_ = Function("q_", None, Real, Bool)
        val ctx_ = Function("ctx_", None, Bool, Bool)

        val s = USubst(SubstitutionPair(ap_, prog) ::
          SubstitutionPair(UnitPredicational("p_", AnyArg), prem1) ::
          SubstitutionPair(UnitPredicational("q_", AnyArg), prem2) ::
          SubstitutionPair(PredicationalOf(ctx_, DotFormula), Diamond(prog, DotFormula)) :: Nil)
        val pr = Provable.rules("CE congruence")(s)
        pr.conclusion shouldBe Sequent(IndexedSeq(), IndexedSeq(conc))
        pr.subgoals should contain only Sequent(IndexedSeq(), IndexedSeq(prem))
      }
  }

  "Congruence rules" should "instantiate CT from y+z=z+y in more context" taggedAs KeYmaeraXTestTags.USubstTest ignore {
    val term1 = "y+z".asTerm
    val term2 = "z+y".asTerm
    val fml = Equal(term1, term2)
    val s = USubst(
      SubstitutionPair(UnitFunctional("f_", AnyArg, Real), term1) ::
      SubstitutionPair(UnitFunctional("g_", AnyArg, Real), term2) ::
      SubstitutionPair(FuncOf(ctxt, DotTerm()), Times(Power(x, Number(3)), DotTerm())) :: Nil)
    val pr = Provable.rules("CT term congruence")(s)
    pr.conclusion shouldBe Sequent(IndexedSeq(), IndexedSeq(Equal(Times(Power(x, Number(3)), term1),
            Times(Power(x, Number(3)), term2))
            ))
    pr.subgoals should be (List(Sequent(IndexedSeq(), IndexedSeq(fml))))
  }

  "Random uniform substitutions" should "have no effect on random expressions without dots" taggedAs KeYmaeraXTestTags.USubstTest in {
    val trm1 = "x^2*y^3".asTerm
    val fml1 = "z1^2*z2>=x".asFormula
    for (i <- 1 to randomTrials) {
      val expr = rand.nextExpression(randomComplexity)
      val randClue = "Expression produced in\n\t " + i + "th run of " + randomTrials +
        " random trials,\n\t generated with " + randomComplexity + " random complexity\n\t from seed " + rand.seed

      val exprString = withSafeClue("Error printing random expression\n\n" + randClue) {
        KeYmaeraXPrettyPrinter.stringify(expr)
      }

      withSafeClue("Random expression " + exprString + "\n\n" + randClue) {
        println("Random dot-free " + expr.prettyString)
        val s = USubst(
          SubstitutionPair(DotTerm(), trm1) ::
            SubstitutionPair(DotFormula, fml1) :: Nil)
        s(expr) shouldBe expr
        expr match {
          case e: Term => s(e) shouldBe expr
          case e: Formula => s(e) shouldBe expr
          case e: DifferentialProgram => s(e) shouldBe expr
          case e: Program => s(e) shouldBe expr
        }
        val dotfml = rand.nextDotFormula(randomComplexity)
        s(dotfml) shouldBe s(dotfml.asInstanceOf[Expression])
        val dottrm = rand.nextDotTerm(randomComplexity)
        s(dottrm) shouldBe s(dottrm.asInstanceOf[Expression])
        val dotprg = rand.nextDotProgram(randomComplexity)
        s(dotprg) shouldBe s(dotprg.asInstanceOf[Expression])
      }
    }
  }

  it should "have no effect on random formulas without that predicate" taggedAs KeYmaeraXTestTags.USubstTest in {
    val trm1 = "x^2*y^3".asTerm
    val fml1 = "z1^2*z2>=x".asFormula
    for (i <- 1 to randomTrials) {
      val fml = rand.nextFormula(randomComplexity)
      val randClue = "Formula produced in\n\t " + i + "th run of " + randomTrials +
        " random trials,\n\t generated with " + randomComplexity + " random complexity\n\t from seed " + rand.seed

      val prgString = withSafeClue("Error printing random formula\n\n" + randClue) {
        KeYmaeraXPrettyPrinter.stringify(fml)
      }

      withSafeClue("Random formula " + prgString + "\n\n" + randClue) {
        println("Random context-free formula " + fml.prettyString)
        val s = USubst(
          SubstitutionPair(DotTerm(), trm1) ::
            SubstitutionPair(PredOf(ctxf, DotTerm()), fml1) :: Nil)
        s(fml) shouldBe fml
        val dotfml = rand.nextDotFormula(randomComplexity)
        println("test on: " + dotfml)
        s(dotfml) shouldBe s(dotfml.asInstanceOf[Expression])
        val dottrm = rand.nextDotTerm(randomComplexity)
        println("test on: " + dottrm)
        s(dottrm) shouldBe s(dottrm.asInstanceOf[Expression])
        val dotprg = rand.nextDotProgram(randomComplexity)
        println("test on: " + dotprg)
        s(dotprg) shouldBe s(dotprg.asInstanceOf[Expression])
      }
    }
  }

  it should "have no effect on random formulas without that predicational" taggedAs KeYmaeraXTestTags.USubstTest in {
    val trm1 = "x^2*y^3".asTerm
    val fml1 = "z1^2*z2>=x".asFormula
    for (i <- 1 to randomTrials) {
      val fml = rand.nextFormula(randomComplexity)
      val randClue = "Formula produced in\n\t " + i + "th run of " + randomTrials +
        " random trials,\n\t generated with " + randomComplexity + " random complexity\n\t from seed " + rand.seed

      val prgString = withSafeClue("Error printing random formula\n\n" + randClue) {
        KeYmaeraXPrettyPrinter.stringify(fml)
      }

      withSafeClue("Random formula " + prgString + "\n\n" + randClue) {
        println("Random context-free formula " + fml.prettyString)
        val s = USubst(
          SubstitutionPair(DotTerm(), trm1) ::
            SubstitutionPair(PredicationalOf(ctx, DotFormula), fml1) :: Nil)
        s(fml) shouldBe fml
        val dotfml = rand.nextDotFormula(randomComplexity)
        s(dotfml) shouldBe s(dotfml.asInstanceOf[Expression])
        val dottrm = rand.nextDotTerm(randomComplexity)
        s(dottrm) shouldBe s(dottrm.asInstanceOf[Expression])
        val dotprg = rand.nextDotProgram(randomComplexity)
        s(dotprg) shouldBe s(dotprg.asInstanceOf[Expression])
      }
    }
  }

  it should "have no effect on other predicationals" taggedAs(CoverageTest) in {
    val fml = "true->P{false} | x>0".asFormula
    USubst(SubstitutionPair(PredicationalOf(Function("q",None,Bool,Bool),DotFormula),True)::Nil)(fml) shouldBe fml
  }

  // apply given context to the given argument
  def contextapp(context: Term, arg: Term) : Term =
   USubst(SubstitutionPair(DotTerm(), arg) :: Nil)(context)

  def contextapp(context: Formula, arg: Term) : Formula =
    USubst(SubstitutionPair(DotTerm(), arg) :: Nil)(context)
  
  def contextapp(context: Formula, arg: Formula) : Formula = {
    val mycontext = Function("dottingC_", None, Bool, Bool)//@TODO eisegesis  should be Function("dottingC_", None, Real->Bool, Bool) //@TODO introduce function types or the Predicational datatype

    USubst(SubstitutionPair(PredicationalOf(mycontext, DotFormula), context) :: Nil)(PredicationalOf(mycontext, arg))
  }
}