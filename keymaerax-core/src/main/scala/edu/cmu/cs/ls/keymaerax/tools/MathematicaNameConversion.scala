/**
 * Copyright (c) Carnegie Mellon University. CONFIDENTIAL
 * See LICENSE.txt for the conditions of this license.
 */
/**
  * @note Code Review: 2016-06-01
  */
package edu.cmu.cs.ls.keymaerax.tools

// favoring immutable Seqs
import scala.collection.immutable._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.tools.MathematicaConversion.MExpr

/**
 * Name conversion to/from Mathematica.
 * @author Nathan Fulton
 * @author Stefan Mitsch
 */
private object MathematicaNameConversion {
  private val PREFIX = "kyx`"
  private val SEP    = "$i$"
  //@todo Code Review: disallow $ in names
  //@solution: insist in maskIdentifier
  private val MUNDERSCORE = "$u$"

  /**
    * Converts a KeYmaera name into a Mathematica symbol. Masks names as follows:
    * base + index     ---> PREFIX + base + SEP + index
    * base only        ---> PREFIX + base
    * special function ---> base
    * @param ns The KeYmaera name to convert.
    * @return The Mathematica symbol.
    */
  def toMathematica(ns: NamedSymbol): MExpr = {
    val name: String = ns match {
      //@note special function
      //@todo Code Review: handle interpreted functions properly, handle name conflicts
      case Function("abs",None,Real,Real) => "Abs"
      case Function("Abs",None,Real,Real) => throw new IllegalArgumentException("Refuse translating Abs to Mathematica to avoid confusion with abs")
      case Function("min",None,Tuple(Real,Real),Real) => "Min"
      case Function("Min",None,Tuple(Real,Real),Real) => throw new IllegalArgumentException("Refuse translating Min to Mathematica to avoid confusion with min")
      case Function("max",None,Tuple(Real,Real),Real) => "Max"
      case Function("Max",None,Tuple(Real,Real),Real) => throw new IllegalArgumentException("Refuse translating Max to Mathematica to avoid confusion with max")
      case _ => maskName(ns)
    }

    new MExpr(com.wolfram.jlink.Expr.SYMBOL, name)
  } ensuring (r => r.symbolQ(), "symbol names expected as result")

  /**
    * Converts a Mathematica name into its corresponding KeYmaera X named symbol (Variable or Function). Distinguishes
    * between variables and functions by the number of arguments (0 args -> Variable, at least 1 arg -> Function). In
    * each case, decomposes the Mathematica name based upon the possible forms of the name:
    * PREFIX + base + SEP + index ---> name + index
    * PREFIX + base               ---> name only
    * base                        ---> special function
    * @param e The Mathematica 'name'
    * @return The named symbol.
    */
  def toKeYmaera(e: MExpr): NamedSymbol = {
    if (e.args.isEmpty) {
      val (name, index) = unmaskName(e.asString())
      Variable(name, index, Real)
    } else unmaskName(e.head().asString()) match {
      //@note special function
      case ("Abs", None) => Function("abs", None, Real, Real)
      case ("Min", None) => Function("min", None, Tuple(Real, Real), Real)
      case ("Max", None) => Function("max", None, Tuple(Real, Real), Real)
      case ("Apply", None) =>
        // nary functions
        val fnName = unmaskName(e.args().head.asString())
        assert(e.args().tail.length == 1)
        val fnDomain = convertFunctionDomain(e.args().tail.head)
        Function(fnName._1, fnName._2, fnDomain, Real)
      case (name, index) =>
        // unary functions
        assert(e.args().length == 1)
        val fnDomain = convertFunctionDomain(e.args().head)
        assert(fnDomain == Real, "Unary function should have domain Real")
        Function(name, index, fnDomain, Real)
    }
  }

  /** Converts a nested list of arguments into nested tuples of reals */
  private def convertFunctionDomain(arg: MExpr): Sort = {
    if (arg.listQ()) {
      assert(arg.args().length == 2)
      Tuple(convertFunctionDomain(arg.args()(0)), convertFunctionDomain(arg.args()(1)))
    } else {
      Real
    }
  }

  /** Masks a name, i.e., replaces _ with $u$, adds the namespace prefix kyx, and merges name and index (separated by $i$) */
  private def maskName(ns: NamedSymbol): String = uncheckedMaskName(ns) ensuring(r => {
    val (name, idx) = uncheckedUnmaskName(r); name == ns.name && idx == ns.index
  }, "Unmasking a masked name should produce original unmasked name" +
    "\n Original unmasked name" + ns.prettyString +
    "\n Masked name " + uncheckedMaskName(ns) +
    "\n Reunmasked to " + uncheckedUnmaskName(uncheckedMaskName(ns)))

  /** Masking without contracts. */
  private def uncheckedMaskName(ns: NamedSymbol): String = {
    //Ensure that none of the "special" strings occur in the variable name.
    insist(!ns.name.contains("\\$"), "Character '$' not allowed in variable names")
    //@note double-check with all separators
    insist(!ns.name.contains(MUNDERSCORE), "String '" + MUNDERSCORE + "' not allowed in variable names")
    insist(!ns.name.contains(PREFIX), "String '" + PREFIX + "' not allowed in variable names")
    insist(!ns.name.contains(SEP), "String '" + SEP + "' not allowed in variable names")

    val identifier = ns.name.replace("_", MUNDERSCORE)

    PREFIX + (ns.index match {
      case Some(idx) => identifier + SEP + idx
      case None      => identifier
    })
  }

  /** Unmasks a name, i.e., adds _ for $u$, removes the namespace prefix kyx, and splits at $i$ into the name and its index. */
  private def unmaskName(maskedName: String): (String, Option[Int]) = uncheckedUnmaskName(maskedName) ensuring(r => {
    r._1 == "Abs" || r._1 == "Min" || r._1 == "Max" || maskName(Variable(r._1, r._2, Real)) == maskedName
  }, "Masking an unmasked name should produce original masked name" +
     "\n Original masked name " + maskedName +
     "\n Unmasked name " + uncheckedUnmaskName(maskedName) +
     "\n Remasked to " + uncheckedMaskName(Variable(uncheckedUnmaskName(maskedName)._1, uncheckedUnmaskName(maskedName)._2, Real)))

  /** Unmasking without contracts. */
  private def uncheckedUnmaskName(maskedName: String): (String, Option[Int]) = {
    def regexOf(s: String) = s.replace("$", "\\$")

    val uscoreMaskedName = maskedName.replaceAll(regexOf(MUNDERSCORE), "_")
    //@todo Code Review: contains --> startsWith, improve readability/prefix+sep handling in a single name
    //@solution: streamlined implementation
    if (uscoreMaskedName.startsWith(PREFIX)) {
      val name = uscoreMaskedName.replace(PREFIX, "")
      if (name.contains(SEP)) {
        // name is of the form thename$i$number, we split into thename and number
        val parts = name.split(regexOf(SEP))
        insist(parts.size == 2, "Expected " + SEP + " once only")
        (parts.head, Some(Integer.parseInt(parts.last)))
      } else (name , None)
    } else (uscoreMaskedName, None)
  }
}
