package btactics

import edu.cmu.cs.ls.keymaerax.Configuration
import edu.cmu.cs.ls.keymaerax.btactics._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser.KeYmaeraXPrettierPrinter
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import testHelper.KeYmaeraXTestTags.SlowTest
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.infrastruct.{PosInExpr, SuccPosition}
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import edu.cmu.cs.ls.keymaerax.tools.ext.{BigDecimalTool, RingsLibrary}
import edu.cmu.cs.ls.keymaerax.tools.qe.BigDecimalQETool

import scala.collection.immutable._
import org.scalatest.LoneElement._

/**
  * @author Fabian Immler
  */
class TaylorModelArithTests extends TacticTestBase {

  val context3 = ("-1 <= x0(), x0() <= 1, -1 <= y0(), y0() <= 1, -1 <= z0(), z0() <= 1," +
    "x = x0() + y0() + rx, -0.01 <= rx, rx <= 0.02," +
    "y = 0.5*x0() - y0() + ry, 0 <= ry, ry <= 0.1").split(',').map(_.asFormula).toIndexedSeq
  implicit val defaultOptions = new TaylorModelOptions {
    override val precision = 5
    override val order = 4
  }
  implicit val defaultTimeStepOptions = new TimeStepOptions {
    def remainderEstimation(i: Integer) = (0.0001, 0.0001)
  }
  lazy val lazyVals = new {
    import PolynomialArithV2._
    val x0 = ofTerm("x0()".asTerm)
    val y0 = ofTerm("y0()".asTerm)
    val tm1 = TaylorModelArith.TM("x".asTerm, x0 + y0, "-0.01".asTerm, "0.02".asTerm, context3, QE)
    val tm2 = TaylorModelArith.TM("y".asTerm, Const(BigDecimal("0.5")) * x0 - y0, "0".asTerm, "0.1".asTerm, context3, QE)
    val third = TaylorModelArith.Exact(ofTerm("1/3".asTerm), context3)
    val tm3 = third *! tm1
    val tm100000 = TaylorModelArith.Exact(ofTerm("0.000001".asTerm), context3) *! tm1
    val tm1234 = TaylorModelArith.Exact(ofTerm("12.34".asTerm), context3) *! tm2
  }
  import lazyVals._

  "Taylor models" should "initialize lazy values" in withMathematica { _ =>
    lazyVals
  }

  it should "add exactly" in withMathematica { qeTool =>
    (tm1 +! tm2).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x+y=1.5*x0()+err_&-0.01<=err_&err_<=0.12)".asFormula
  }

  it should "add approximately" in withMathematica { qeTool =>
    (tm1 +! tm100000).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x+0.000001*x=1.000001*y0()+1.000001*x0()+err_&-0.010001<=err_&err_<=0.020001)".asFormula
    (tm1 + tm100000).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x+0.000001*x=y0()+x0()+err_&-0.010003<=err_&err_<=0.020003)".asFormula
  }

  it should "subtract exactly" in withMathematica { qeTool =>
    (tm1 -! tm2).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x-y=2*y0()+0.5*x0()+err_&-0.11<=err_&err_<=0.02)".asFormula
  }

  it should "subtract approximately" in withMathematica { qeTool =>
    (tm1 -! tm100000).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x-0.000001*x=0.999999*y0()+0.999999*x0()+err_&-0.010001<=err_&err_<=0.020001)".asFormula
    (tm1 - tm100000).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x-0.000001*x=0.9999*y0()+0.9999*x0()+err_&-0.010199<=err_&err_<=0.020199)".asFormula
  }

  it should "multiply exactly" in withMathematica { qeTool =>
    (tm1 *! tm2).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x*y=-y0()^2+-(0.5*x0()*y0())+0.5*x0()^2+err_&(-0.231)<=err_&err_<=0.232)".asFormula
  }

  it should "multiply approximately" in withMathematica { qeTool =>
    (tm1234 *! tm1234).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (12.34*y*(12.34*y)=152.2756*y0()^2+-(152.27560*x0()*y0())+38.068900*x0()^2+err_&(-45.684)<=err_&err_<=47.207)".asFormula
    (tm1234 * tm1234).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (12.34*y*(12.34*y)=152.2*y0()^2+-(152.3*x0()*y0())+38.06*x0()^2+err_&(-45.793)<=err_&err_<=47.316)".asFormula
  }

  it should "negate" in withMathematica { qeTool =>
    (-tm1).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (-x=-y0()+-x0()+err_&(-0.02)<=err_&err_<=0.01)".asFormula
  }

  it should "square exactly" in withMathematica { qeTool =>
    tm1.squareExact.prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x^2=y0()^2+2*x0()*y0()+x0()^2+err_&(-0.08)<=err_&err_<=0.0804)".asFormula
  }

  it should "square approximately" in withMathematica { qeTool =>
    tm1234.squareExact.prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ ((12.34*y)^2=152.2756*y0()^2+-(152.27560*x0()*y0())+38.068900*x0()^2+err_&(-45.683)<=err_&err_<=47.206)".asFormula
    tm1234.square.prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ ((12.34*y)^2=152.2*y0()^2+-(152.3*x0()*y0())+38.06*x0()^2+err_&(-45.792)<=err_&err_<=47.315)".asFormula
  }

  it should "^1" in withMathematica { qeTool =>
    (tm1^1).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x^1=y0()+x0()+err_&-0.01<=err_&err_<=0.02)".asFormula
  }

  it should "^(2*n)" in withMathematica { qeTool =>
    (tm1^4).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x^4=y0()^4+4*x0()*y0()^3+6*x0()^2*y0()^2+4*x0()^3*y0()+x0()^4+err_&(-0.64)<=err_&err_<=0.64967)".asFormula
  }
  it should "^(2*n + 1)" in withMathematica { qeTool =>
    (tm1^3).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x^3=y0()^3+3*x0()*y0()^2+3*x0()^2*y0()+x0()^3+err_&(-0.2024)<=err_&err_<=0.24241)".asFormula
  }

  it should "/!const" in withMathematica { qeTool =>
    (tm1 /! 3).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x/3=1/3*y0()+1/3*x0()+err_&(-0.0033334)<=err_&err_<=0.0066668)".asFormula
  }
  it should "/const" in withMathematica { qeTool =>
    (tm1 / 3).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ (x/3=0.3333*y0()+0.3333*x0()+err_&(-0.0034001)<=err_&err_<=0.0067335)".asFormula
  }

  it should "exponentiate approximately" in withMathematica { qeTool =>
    (tm1234^3).prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ ((12.34*y)^3=-(1879*y0()^3)+2818*x0()*y0()^2+-(1410*x0()^2*y0())+234.8*x0()^3+err_&(-1122.4)<=err_&err_<=1359.0)".asFormula
  }

  it should "exact" in withMathematica { qeTool =>
    import TaylorModelArith._
    import PolynomialArithV2._
    val a = ofTerm("x0()".asTerm)
    val b = ofTerm("1".asTerm)
    val c = ofTerm("1/3".asTerm)
    val tmA = Exact(a, context3)
    val tmB = Exact(b, context3)
    val tmC = Exact(c, context3)
    (tmA).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (x0()=x0()+err_&0<=err_&err_<=0)".asFormula
    (tmB).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (1=1+err_&0<=err_&err_<=0)".asFormula
    (tmC).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (1/3=1/3+err_&0<=err_&err_<=0)".asFormula
  }

  it should "approx" in withMathematica { qeTool =>
    val tm = (tm3 +! tm2).squareExact
    val tmA = tm.approx
    tmA.prettyPrv.conclusion.succ(0) shouldBe
      "\\exists err_ ((1/3*x+y)^2=0.4444*y0()^2+-(1.112*x0()*y0())+0.6944*x0()^2+err_&(-0.32102)<=err_&err_<=0.33240)".asFormula
  }

  it should "collect higher order terms" in withMathematica { qeTool =>
    import TaylorModelArith._
    import PolynomialArithV2._
    val tm = (tm3 + tm2 + third) ^ 3
    val res0 = tm.collectHigherOrderTerms(new TaylorModelOptions { val precision = defaultOptions.precision; val order = 0})
    val res1 = tm.collectHigherOrderTerms(new TaylorModelOptions { val precision = defaultOptions.precision; val order = 1})
    res0.prettyPrv.conclusion.succ.loneElement shouldBe
      "\\exists err_ ((1/3*x+y+1/3)^3=0.03699+err_&(-6.8403)<=err_&err_<=7.2716)".asFormula
    res1.prettyPrv.conclusion.succ.loneElement shouldBe
      "\\exists err_ ((1/3*x+y+1/3)^3=0.03699+-(0.2222*y0())+0.2776*x0()+err_&(-6.3405)<=err_&err_<=6.7718)".asFormula
  }

  it should "interval" in withMathematica { qeTool =>
    import TaylorModelArith._
    import PolynomialArithV2._
    val tm = (tm3 + tm2 + third) ^ 3
    tm.interval._1 shouldBe "(-68034)*10^(-4)".asTerm
    tm.interval._2 shouldBe "73086*10^(-4)".asTerm
    tm.interval._3.conclusion.succ.loneElement shouldBe
      "(-68034)*10^(-4)<=(1/3*x+y+1/3)^3&(1/3*x+y+1/3)^3<=73086*10^(-4)".asFormula
  }

  it should "drop empty interval" in withMathematica { qeTool =>
    val context = IndexedSeq("x = 1.4 + 0.15 * e0".asFormula)
    val x = TaylorModelArith.TM("x".asTerm, PolynomialArithV2.ofTerm("1.4 + 0.15 * e0".asTerm), Number(0), Number(0), context, QE)
    x.dropEmptyInterval.get.conclusion.succ.loneElement shouldBe "x=0+1.4/1*1+0+0.15/1*(1*e0^1)+0".asFormula
  }

  it should "prettyPrv" in withMathematica { _ =>
    println(TaylorModelArith.TM("e0".asTerm, PolynomialArithV2.ofTerm("e0".asTerm), Number(0), Number(0), IndexedSeq(), QE).prettyPrv)
  }

  it should "evaluate" in withMathematica { _ =>
        val context = ("x = 1/3 * x0()^2 + 1/5 * x0() * y0() + 5/8 * y0()^2, -1 <= x0(), x0() <= 1, -1 <= y0(), y0() <= 1," +
      "x0() <= 1/100*y0() + 0.0001," +
      "x0() >= 1/100*y0()," +
      "y0() = 12.34").split(',').map(_.asFormula).toIndexedSeq
    val x = TaylorModelArith.TM("x".asTerm, PolynomialArithV2.ofTerm("1/3 * x0()^2 + 1/5 * x0() * y0() + 5/8 * y0()^2".asTerm),
      Number(0),
      Number(0),
      context,
      QE).approx
    val x0 = TaylorModelArith.TM("x0()".asTerm, PolynomialArithV2.ofTerm("1/100*y0()".asTerm), Number(0), Number(0.0001), context, QE)
    val y0 = TaylorModelArith.TM("y0()".asTerm, PolynomialArithV2.ofTerm("12.34".asTerm), Number(0), Number(0), context, QE)
    val xFml = "\\exists err_ (x=0.625*y0()^2+0.2*x0()*y0()+0.3333*x0()^2+err_&(-0.000033334)<=err_&err_<=0.000033334)".asFormula
    x.prettyPrv.conclusion.succ(0) shouldBe xFml
    x.evaluate(Seq()).prettyPrv.conclusion.succ(0) shouldBe xFml
    x.evaluate(Seq(x0)).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (x=0.6270*y0()^2+err_&0.0050419<=err_&err_<=0.0053640)".asFormula
    x.evaluate(Seq(y0)).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (x=95.16+2.468*x0()+0.3333*x0()^2+err_&0.012216<=err_&err_<=0.012284)".asFormula
    x.evaluate(Seq(x0, y0)).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (x=95.16+0.02468*y0()+0.00003333*y0()^2+err_&0.012216<=err_&err_<=0.012540)".asFormula // @note insertion is simultaneous and not recursive
    x.evaluate(Seq(x0)).evaluate(Seq(y0)).prettyPrv.conclusion.succ(0) shouldBe "\\exists err_ (x=95.47+err_&0.011843<=err_&err_<=0.012166)".asFormula
  }

  it should "weakenContext" in withMathematica { _ =>
    val prv = tm1.weakenContext("P()".asFormula).prv
    prv shouldBe 'proved
    prv.conclusion.ante shouldBe tm1.prv.conclusion.ante++Seq("P()".asFormula)
  }

  it should "trimContext" in {
    TaylorModelArith.trimContext("0 <= x(), x() <= 1, x <= y, -1 <= y(), y() <= 1".split(',').map(_.asFormula).toIndexedSeq,
      Seq("x()".asTerm, "y()".asTerm)).loneElement shouldBe "x<=y".asFormula
  }

  // TODO: generate a context like this from "x : [1.25, 1.55]" and "y : [2.35, 2.45]"?!
  val vdpContext = "t = 0, x = 1.4 + 0.15 * e0, y = 2.4 + 0.05 * e1, -1 <= e0, e0 <= 1, -1 <= e1, e1 <= 1".split(',').map(_.asFormula).toIndexedSeq
  val vdpProgram = "{x' = y, y' = (1 - x^2)*y - x,t'=1}".asDifferentialProgram
  lazy val vdpX = TaylorModelArith.TM("x".asTerm, PolynomialArithV2.ofTerm("1.4 + 0.15 * e0".asTerm), Number(0), Number(0), vdpContext, QE)
  lazy val vdpY = TaylorModelArith.TM("y".asTerm, PolynomialArithV2.ofTerm("2.4 + 0.05 * e1".asTerm), Number(0), Number(0), vdpContext, QE)
  def vdpLemmas(order: Int) = new TaylorModelArith.TemplateLemmas(vdpProgram, order)

  "timeStepLemma" should "van der Pol" in withMathematica { qeTool =>
    withTemporaryConfig(Map(Configuration.Keys.QE_ALLOW_INTERPRETED_FNS -> "true")) {
      val context = vdpContext
      val vdp = vdpLemmas(3)
      val x = vdpX
      val y = vdpY
      val r0 = TaylorModelArith.TM("e0".asTerm, PolynomialArithV2.ofTerm("e0".asTerm), Number(0), Number(0), context, QE)
      val r1 = TaylorModelArith.TM("e1".asTerm, PolynomialArithV2.ofTerm("e1".asTerm), Number(0), Number(0), context, QE)
      val t = proveBy(Sequent(context, IndexedSeq("t = 0".asFormula)), closeId)
      val res = new vdp.TimeStep(Seq(x, y), Seq(r0, r1), t, 0.01).timeStepLemma
      // println(new KeYmaeraXPrettierPrinter(100).stringify(res.conclusion.succ.loneElement))
      res.conclusion.succ.loneElement shouldBe
        """[{x'=y, y'=(1 - x^2) * y - x, t'=1 & 0 <= t & t <= 0 + 0.01}]
          |  \exists s
          |    (
          |      (t = 0 + s & 0 <= s & s <= 0.01) &
          |      \exists Rem0
          |        (
          |          x =
          |          1.4 + 0.15 * e0 + 0 * e1 + 2.4 * s + 0.05 * (s * e1) + (-1.8520) * s^2 + 0 * (s * e0) +
          |          (-0.02400) * (s^2 * e1) +
          |          (-2.4954) * s^3 +
          |          (-0.5790) * (s^2 * e0) +
          |          Rem0 &
          |          s * ((-8724) * 10^(-7)) + 0.00 <= Rem0 & Rem0 <= s * (4131 * 10^(-7)) + 0.00
          |        ) &
          |      \exists Rem1
          |        (
          |          y =
          |          2.4 + 0 * e0 + 0.05 * e1 + (-3.704) * s + (-0.0480) * (s * e1) + (-7.48605) * s^2 +
          |          (-1.1580) * (s * e0) +
          |          (-0.33796) * (s^2 * e1) +
          |          (-0.05400) * (s * e0^2) +
          |          10.850 * s^3 +
          |          0.46965 * (s^2 * e0) +
          |          (-0.02100) * (s * e0 * e1) +
          |          0.000 * (s * e1^2) +
          |          Rem1 &
          |          s * ((-3991) * 10^(-6)) + 0.00 <= Rem1 & Rem1 <= s * (1096 * 10^(-5)) + 0.00
          |        )
          |    )""".stripMargin.asFormula
    }
  }

  "timeStepLemma(P)" should "van der Pol" in withMathematica { qeTool =>
    withTemporaryConfig(Map(Configuration.Keys.QE_ALLOW_INTERPRETED_FNS -> "true")) {
      val context = vdpContext
      val vdp = vdpLemmas(1)
      val x = vdpX
      val y = vdpY
      val r0 = TaylorModelArith.TM("e0".asTerm, PolynomialArithV2.ofTerm("e0".asTerm), Number(0), Number(0), context, QE)
      val r1 = TaylorModelArith.TM("e1".asTerm, PolynomialArithV2.ofTerm("e1".asTerm), Number(0), Number(0), context, QE)
      val t = proveBy(Sequent(context, IndexedSeq("t = 0".asFormula)), closeId)
      val (res, tmIvls, (tmEqs, t1Eq)) = new vdp.TimeStep(Seq(x, y), Seq(r0, r1), t, 0.01).timeStepLemma("Safe(t, y, x)".asFormula)
      // println(new KeYmaeraXPrettierPrinter(100).stringify(res))
      val contextE = "(-1) <= e0, e0 <= 1, (-1) <= e1, e1 <= 1".split(',').toIndexedSeq.map(_.asFormula)
      res.subgoals.length shouldBe 2
      res.conclusion.ante shouldBe context
      res.conclusion.succ.loneElement shouldBe "[{x'=y, y'=(1 - x^2) * y - x, t'=1}]Safe(t,y,x)".asFormula

      res.subgoals(0).ante shouldBe (contextE ++
        IndexedSeq(
          "0 <= s",
          "s <= 0.01",
          "\\exists err_(x = 1.4 + 2.4 * s + 0.15 * e0 + err_ & (-1020) * 10 ^ (-6) <= err_ & err_ <= 6286 * 10 ^ (-7))",
          "\\exists err_ (y = 2.4 + -(3.704 * s) + 0.05 * e1 + err_ & (-1467) * 10 ^ (-5) <= err_ & err_ <= 1258 * 10 ^ (-5))").map(_.asFormula))
      res.subgoals(0).succ.loneElement shouldBe "Safe(0 + s,y,x)".asFormula

      res.subgoals(1).ante shouldBe (contextE ++
        IndexedSeq("t = 0.01",
          "\\exists err_ (x = 1.424 + 0.15 * e0 + err_ & (-1020) * 10 ^ (-6) <= err_ & err_ <= 6286 * 10 ^ (-7))",
          "\\exists err_ (y = 2.36296 + 0.05 * e1 + err_ & (-1467) * 10 ^ (-5) <= err_ & err_ <= 1258 * 10 ^ (-5))").map(_.asFormula)
        )
      res.subgoals(1).succ.loneElement shouldBe
        "[{x'=y, y'=(1 - x^2) * y - x, t'=1}]Safe(t,y,x)".asFormula
    }
  }

}
