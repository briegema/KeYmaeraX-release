/**
 * Copyright (c) Carnegie Mellon University.
 * See LICENSE.txt for the conditions of this license.
 */
package edu.cmu.cs.ls.keymaerax.hydra.responses.proofs

import edu.cmu.cs.ls.keymaerax.hydra.Helpers.nodeJson
import edu.cmu.cs.ls.keymaerax.hydra.{ProofTreeNode, Response}
import spray.json.{JsArray, JsNumber, JsObject, JsValue}

class GetPathAllResponse(path: List[ProofTreeNode], parentsRemaining: Int, marginLeft: Int, marginRight: Int) extends Response {
  def getJson: JsValue =
    JsObject (
      "numParentsUntilComplete" -> JsNumber(parentsRemaining),
      "path" -> JsArray(path.map(nodeJson(_, withSequent=true, marginLeft, marginRight)._2):_*)
    )
}
