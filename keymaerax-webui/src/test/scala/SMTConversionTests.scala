/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.tools._
import edu.cmu.cs.ls.keymaerax.tools.qe.DefaultSMTConverter
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

/**
 * Created by ran on 3/23/15.
 * @author Ran Ji
 */
class SMTConversionTests extends FlatSpec with Matchers with BeforeAndAfterEach {
  type KExpr = edu.cmu.cs.ls.keymaerax.core.Expression

  private val converter = DefaultSMTConverter

  "Numbers" should "convert numbers" in {
    converter("1 > 2".asFormula) should be("(assert (not (> 1 2)))\n(check-sat)\n")
  }

  it should "convert legal big positive numbers" in {
    converter("9223372036854775807 > 1".asFormula) should be("(assert (not (> 9223372036854775807 1)))\n(check-sat)\n")
  }

  it should "convert legal big negative numbers" in {
    converter("-9223372036854775807 < 1".asFormula) should be("(assert (not (< (- 9223372036854775807) 1)))\n(check-sat)\n")
  }

  it should "throw exception in converting illegal big positive numbers" in {
    a [ConversionException] should be thrownBy converter("9223372036854775808 > 1".asFormula)
  }

  it should "throw exception in converting illegal big negative numbers" in {
    a [ConversionException] should be thrownBy converter("-9223372036854775808 < 1".asFormula)
  }

  "Variables" should "convert numbers" in {
    converter("x > 2".asFormula) should be ("(declare-fun _v_x () Real)\n(assert (not (> _v_x 2)))\n(check-sat)\n")
  }

  it should "convert numbers complex" in {
    converter("x > 2 & y < 3".asFormula) should be ("(declare-fun _v_x () Real)\n" + "(declare-fun _v_y () Real)\n"
      + "(assert (not (and (> _v_x 2) (< _v_y 3))))\n(check-sat)\n")
  }

  it should "convert numbers const" in {
    converter("x > a & y < x".asFormula) should be("(declare-fun _v_a () Real)\n"
      + "(declare-fun _v_x () Real)\n" + "(declare-fun _v_y () Real)\n" + "(assert (not (and (> _v_x _v_a) (< _v_y _v_x))))\n(check-sat)\n")
  }

  "Constant function" should "convert" in {
    converter("g() > 0".asFormula) should be ("(declare-fun _f_g () Real)\n"
      + "(assert (not (> _f_g 0)))\n(check-sat)\n")
  }

  "Quantifiers" should "convert forall" in {
    converter("\\forall x x>y()".asFormula) should be ("(declare-fun _v_x () Real)\n(declare-fun _f_y () Real)\n(assert (not (forall ((_v_x Real)) (> _v_x _f_y))))\n(check-sat)\n")
  }

  it should "convert exists" in {
    converter("\\exists x x>y()".asFormula) should be ("(declare-fun _v_x () Real)\n" + "(declare-fun _f_y () Real)\n"
      + "(assert (not (exists ((_v_x Real)) (> _v_x _f_y))))\n(check-sat)\n")
  }

  it should "convert nested qutifiers" in {
    converter("\\forall x \\exists y x>y".asFormula) should be ("(declare-fun _v_x () Real)\n" + "(declare-fun _v_y () Real)\n"
      + "(assert (not (forall ((_v_x Real)) (exists ((_v_y Real)) (> _v_x _v_y)))))\n(check-sat)\n")
  }

  it should "convert complex quantifiers" in {
    //@note expected result depends on whether we keep ^n or unroll to x*x*...
//    converter("\\forall x \\forall y \\exists z x^2+y^2=z^2".asFormula) should be ("(declare-fun _x () Real)\n" + "(declare-fun _y () Real)\n" + "(declare-fun _z () Real)\n"
//      + "(assert (not (forall ((_x Real) (_y Real)) (exists ((_z Real)) (= (+ (^ _x 2) (^ _y 2)) (^ _z 2))))))\n(check-sat)\n")
    converter("\\forall x \\forall y \\exists z x^2+y^2=z^2".asFormula) should be ("(declare-fun _v_x () Real)\n" + "(declare-fun _v_y () Real)\n" + "(declare-fun _v_z () Real)\n"
      + "(assert (not (forall ((_v_x Real) (_v_y Real)) (exists ((_v_z Real)) (= (+ (* _v_x (* _v_x (^ _v_x 0))) (* _v_y (* _v_y (^ _v_y 0)))) (* _v_z (* _v_z (^ _v_z 0))))))))\n(check-sat)\n")
  }

  "Exponentials" should "convert positive" in {
    //@note expected result depends on whether we keep ^n or unroll to x*x*..., which might change
    //converter("3^3 > 1".asFormula) should be  ("(assert (not (> (^ 3 3) 1)))\n(check-sat)\n")
    converter("3^3 > 1".asFormula) should be  ("(assert (not (> (* 3 (* 3 (* 3 (^ 3 0)))) 1)))\n(check-sat)\n")
  }

  it should "convert negative" in {
    converter("3^-2 < 1".asFormula) should be ("(assert (not (< (^ 3 (- 2)) 1)))\n(check-sat)\n")
  }

  it should "convert index 0" in {
    converter("(x+y-z)^(1-1) = 1".asFormula) should be ("(declare-fun _v_x () Real)\n(declare-fun _v_y () Real)\n(declare-fun _v_z () Real)\n(assert (not (= (^ (- (+ _v_x _v_y) _v_z) (- 1 1)) 1)))\n(check-sat)\n")
  }

  it should "convert base 0" in {
    converter("(0+0)^(x+y-1) = 1".asFormula) should be ("(declare-fun _v_x () Real)\n(declare-fun _v_y () Real)\n(assert (not (= (^ (+ 0 0) (- (+ _v_x _v_y) 1)) 1)))\n(check-sat)\n")
  }

  it should "convert fractions" in {
    converter("3^(-0.5) < 1".asFormula) shouldBe "(assert (not (< (^ 3 (- 0.5)) 1)))\n(check-sat)\n"
  }

  it should "convert complex" in {
    //@note expected result depends on whether we keep ^n or unroll to x*x*...
//    converter("(x+y-z)^3 = 1".asFormula) should be ("(declare-fun _x () Real)\n" + "(declare-fun _y () Real)\n" + "(declare-fun _z () Real)\n"
//      + s"(assert (not (= (^ (- (+ _x _y) _z) 3) 1)))\n(check-sat)\n")
    val t = "(- (+ _v_x _v_y) _v_z)"
    converter("(x+y-z)^3 = 1".asFormula) should be ("(declare-fun _v_x () Real)\n" + "(declare-fun _v_y () Real)\n" + "(declare-fun _v_z () Real)\n"
      + s"(assert (not (= (* $t (* $t (* $t (^ $t 0)))) 1)))\n(check-sat)\n")
  }

  it should "convert complex 2" in {
    //@note expected result depends on whether we keep ^n or unroll to x*x*...
//    converter("(x+x+x)^3 = 1".asFormula) should be ("(declare-fun _x () Real)\n(assert (not (= (^ (+ (+ _x _x) _x) 3) 1)))\n(check-sat)\n")
    val t = "(+ (+ _v_x _v_x) _v_x)"
    converter("(x+x+x)^3 = 1".asFormula) shouldBe s"(declare-fun _v_x () Real)\n(assert (not (= (* $t (* $t (* $t (^ $t 0)))) 1)))\n(check-sat)\n"
  }

  it should "convert complex 3" in {
    converter("(x+y-z)^(y*2-y-y+3) = 1".asFormula) should be ("(declare-fun _v_x () Real)\n" + "(declare-fun _v_y () Real)\n" + "(declare-fun _v_z () Real)\n"
      + "(assert (not (= (^ (- (+ _v_x _v_y) _v_z) (+ (- (- (* _v_y 2) _v_y) _v_y) 3)) 1)))\n(check-sat)\n")
  }

  // complex
  it should "convert bouncing ball" in {
    //@note expected result depends on whether we keep ^n or unroll to x*x*...
//    converter("c<1 & c>=0 & H>=0 & g>0 & v^2<=2*g*(H-h) & h>=0 & kxtime_1=0 & h_2=h & v_2=v & kxtime_4=kxtime_1 & v_3=-1*kxtime_5*g+v_2 & h_3=1/2*(-1*kxtime_5^2*g+2*h_2+2*kxtime_5*v_2) & h_3>=0 & kxtime_5>=kxtime_4 & h_3=0 & v_5=-c*v_3 -> v_5^2<=2*g*(H-h_3)".asFormula) should be (
//      """(declare-fun _H () Real)
//        |(declare-fun _c () Real)
//        |(declare-fun _g () Real)
//        |(declare-fun _h () Real)
//        |(declare-fun _h_2 () Real)
//        |(declare-fun _h_3 () Real)
//        |(declare-fun _kxtime_1 () Real)
//        |(declare-fun _kxtime_4 () Real)
//        |(declare-fun _kxtime_5 () Real)
//        |(declare-fun _v () Real)
//        |(declare-fun _v_2 () Real)
//        |(declare-fun _v_3 () Real)
//        |(declare-fun _v_5 () Real)
//        |(assert (not (=> (and (< _c 1) (and (>= _c 0) (and (>= _H 0) (and (> _g 0) (and (<= (^ _v 2) (* (* 2 _g) (- _H _h))) (and (>= _h 0) (and (= _kxtime_1 0) (and (= _h_2 _h) (and (= _v_2 _v) (and (= _kxtime_4 _kxtime_1) (and (= _v_3 (+ (* (* (- 1) _kxtime_5) _g) _v_2)) (and (= _h_3 (* (/ 1 2) (+ (+ (* (* (- 1) (^ _kxtime_5 2)) _g) (* 2 _h_2)) (* (* 2 _kxtime_5) _v_2)))) (and (>= _h_3 0) (and (>= _kxtime_5 _kxtime_4) (and (= _h_3 0) (= _v_5 (- (* _c _v_3)))))))))))))))))) (<= (^ _v_5 2) (* (* 2 _g) (- _H _h_3))))))
//        |(check-sat)
//        |""".stripMargin)
    converter("c<1 & c>=0 & H>=0 & g>0 & v^2<=2*g*(H-h) & h>=0 & kxtime_1=0 & h_2=h & v_2=v & kxtime_4=kxtime_1 & v_3=-1*kxtime_5*g+v_2 & h_3=1/2*(-1*kxtime_5^2*g+2*h_2+2*kxtime_5*v_2) & h_3>=0 & kxtime_5>=kxtime_4 & h_3=0 & v_5=-c*v_3 -> v_5^2<=2*g*(H-h_3)".asFormula) should be (
      """(declare-fun _v_H () Real)
        |(declare-fun _v_c () Real)
        |(declare-fun _v_g () Real)
        |(declare-fun _v_h () Real)
        |(declare-fun _v_h_2 () Real)
        |(declare-fun _v_h_3 () Real)
        |(declare-fun _v_kxtime_1 () Real)
        |(declare-fun _v_kxtime_4 () Real)
        |(declare-fun _v_kxtime_5 () Real)
        |(declare-fun _v_v () Real)
        |(declare-fun _v_v_2 () Real)
        |(declare-fun _v_v_3 () Real)
        |(declare-fun _v_v_5 () Real)
        |(assert (not (=> (and (< _v_c 1) (and (>= _v_c 0) (and (>= _v_H 0) (and (> _v_g 0) (and (<= (* _v_v (* _v_v (^ _v_v 0))) (* (* 2 _v_g) (- _v_H _v_h))) (and (>= _v_h 0) (and (= _v_kxtime_1 0) (and (= _v_h_2 _v_h) (and (= _v_v_2 _v_v) (and (= _v_kxtime_4 _v_kxtime_1) (and (= _v_v_3 (+ (* (* (- 1) _v_kxtime_5) _v_g) _v_v_2)) (and (= _v_h_3 (* (/ 1 2) (+ (+ (* (* (- 1) (* _v_kxtime_5 (* _v_kxtime_5 (^ _v_kxtime_5 0)))) _v_g) (* 2 _v_h_2)) (* (* 2 _v_kxtime_5) _v_v_2)))) (and (>= _v_h_3 0) (and (>= _v_kxtime_5 _v_kxtime_4) (and (= _v_h_3 0) (= _v_v_5 (- (* _v_c _v_v_3)))))))))))))))))) (<= (* _v_v_5 (* _v_v_5 (^ _v_v_5 0))) (* (* 2 _v_g) (- _v_H _v_h_3))))))
        |(check-sat)
        |""".stripMargin)
  }

  it should "convert bouncing ball constfun" in {
//    converter("c<1 & c>=0 & H>=0 & g()>0 & v^2<=2*g()*(H-h) & h>=0 & kxtime_1=0 & h_2()=h & v_2()=v & kxtime_4()=kxtime_1 & v_3=-1*kxtime_5*g()+v_2() & h_3=1/2*(-1*kxtime_5^2*g()+2*h_2()+2*kxtime_5*v_2()) & h_3>=0 & kxtime_5>=kxtime_4() & h_3=0 & v_5=-c*v_3 -> v_5^2<=2*g()*(H-h_3)".asFormula) should be (
//      """(declare-fun _H () Real)
//        |(declare-fun _c () Real)
//        |(declare-fun _g () Real)
//        |(declare-fun _h () Real)
//        |(declare-fun _h_2 () Real)
//        |(declare-fun _h_3 () Real)
//        |(declare-fun _kxtime_1 () Real)
//        |(declare-fun _kxtime_4 () Real)
//        |(declare-fun _kxtime_5 () Real)
//        |(declare-fun _v () Real)
//        |(declare-fun _v_2 () Real)
//        |(declare-fun _v_3 () Real)
//        |(declare-fun _v_5 () Real)
//        |(assert (not (=> (and (< _c 1) (and (>= _c 0) (and (>= _H 0) (and (> _g 0) (and (<= (^ _v 2) (* (* 2 _g) (- _H _h))) (and (>= _h 0) (and (= _kxtime_1 0) (and (= _h_2 _h) (and (= _v_2 _v) (and (= _kxtime_4 _kxtime_1) (and (= _v_3 (+ (* (* (- 1) _kxtime_5) _g) _v_2)) (and (= _h_3 (* (/ 1 2) (+ (+ (* (* (- 1) (^ _kxtime_5 2)) _g) (* 2 _h_2)) (* (* 2 _kxtime_5) _v_2)))) (and (>= _h_3 0) (and (>= _kxtime_5 _kxtime_4) (and (= _h_3 0) (= _v_5 (- (* _c _v_3)))))))))))))))))) (<= (^ _v_5 2) (* (* 2 _g) (- _H _h_3))))))
//        |(check-sat)
//        |""".stripMargin)
    converter("c<1 & c>=0 & H>=0 & g()>0 & v^2<=2*g()*(H-h) & h>=0 & kxtime_1=0 & h_2()=h & v_2()=v & kxtime_4()=kxtime_1 & v_3=-1*kxtime_5*g()+v_2() & h_3=1/2*(-1*kxtime_5^2*g()+2*h_2()+2*kxtime_5*v_2()) & h_3>=0 & kxtime_5>=kxtime_4() & h_3=0 & v_5=-c*v_3 -> v_5^2<=2*g()*(H-h_3)".asFormula) should be (
      """(declare-fun _v_H () Real)
        |(declare-fun _v_c () Real)
        |(declare-fun _f_g () Real)
        |(declare-fun _v_h () Real)
        |(declare-fun _f_h_2 () Real)
        |(declare-fun _v_h_3 () Real)
        |(declare-fun _v_kxtime_1 () Real)
        |(declare-fun _f_kxtime_4 () Real)
        |(declare-fun _v_kxtime_5 () Real)
        |(declare-fun _v_v () Real)
        |(declare-fun _f_v_2 () Real)
        |(declare-fun _v_v_3 () Real)
        |(declare-fun _v_v_5 () Real)
        |(assert (not (=> (and (< _v_c 1) (and (>= _v_c 0) (and (>= _v_H 0) (and (> _f_g 0) (and (<= (* _v_v (* _v_v (^ _v_v 0))) (* (* 2 _f_g) (- _v_H _v_h))) (and (>= _v_h 0) (and (= _v_kxtime_1 0) (and (= _f_h_2 _v_h) (and (= _f_v_2 _v_v) (and (= _f_kxtime_4 _v_kxtime_1) (and (= _v_v_3 (+ (* (* (- 1) _v_kxtime_5) _f_g) _f_v_2)) (and (= _v_h_3 (* (/ 1 2) (+ (+ (* (* (- 1) (* _v_kxtime_5 (* _v_kxtime_5 (^ _v_kxtime_5 0)))) _f_g) (* 2 _f_h_2)) (* (* 2 _v_kxtime_5) _f_v_2)))) (and (>= _v_h_3 0) (and (>= _v_kxtime_5 _f_kxtime_4) (and (= _v_h_3 0) (= _v_v_5 (- (* _v_c _v_v_3)))))))))))))))))) (<= (* _v_v_5 (* _v_v_5 (^ _v_v_5 0))) (* (* 2 _f_g) (- _v_H _v_h_3))))))
        |(check-sat)
        |""".stripMargin)
  }
}
