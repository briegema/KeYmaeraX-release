/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
package edu.cmu.cs.ls.keymaerax.hydra

import java.security.SecureRandom
import java.util.{Calendar, Date}

import _root_.edu.cmu.cs.ls.keymaerax.btactics.DerivationInfo
import akka.event.slf4j.SLF4JLogging
import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import akka.actor.Actor
import spray.http.CacheDirectives.{`max-age`, `no-cache`}
import spray.http.HttpHeaders.`Cache-Control`
import spray.routing._
import spray.http._
import spray.json._
import spray.routing
import spray.util.LoggingContext

class RestApiActor extends Actor with RestApi {
  implicit def eh(implicit log: LoggingContext) = ExceptionHandler {
    case e: Throwable => ctx =>
      val errorJson: String = new ErrorResponse(e.getMessage, e).getJson.prettyPrint
      log.error(e, s"Request '${ctx.request.uri}' resulted in uncaught exception", ctx.request)
      ctx.complete(StatusCodes.InternalServerError, errorJson)
  }

  def actorRefFactory = context

  //Note: separating the actor and router allows testing of the router without
  //spinning up an actor.
  def receive = runRoute(myRoute)

}

/**
 * RestApi is the API router. See README.md for a description of the API.
 */
trait RestApi extends HttpService with SLF4JLogging {
  val database = DBAbstractionObj.defaultDatabase //SQLite //Not sure when or where to create this... (should be part of Boot?)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Helper Methods
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getUserFromUserIdCookieContent(userIdContent : String):String = userIdContent //for now...

  private def getFileContentsFromFormData(item : BodyPart) : String = {
    val entity = item.entity
    val headers = item.headers
    val content = item.entity.data.asString

    //Just FYI here's how you get the content type...
    val contentType = headers.find(h => h.is("content-type")).get.value

    content
  }

  private def getFileNameFromFormData(item:BodyPart) : String = {
    item.headers.find(h => h.is("content-disposition")).get.value.split("filename=").last
  }

  /**
    * Turn off all caching.
    *
    * @note A hosted version of the server should probably turn this off.
    * */
  private def completeWithoutCache(response: String) =
    respondWithHeader(`Cache-Control`(Seq(`no-cache`, `max-age`(0)))) {
      super.complete(response)
    }

  private def standardCompletion(r: Request, t: SessionToken) : String = t match {
    case NewlyExpiredToken(t) => {
      completeResponse(new SessionExpiredResponse() :: Nil)
    }
    case _ => {
      val responses = r.getResultingResponses(t)
      completeResponse(responses)
    }
  }

  /** @note you probably don't actually want to use this. Use standardCompletion instead. */
  private def completeResponse(responses: List[Response]): String = {
    //@note log all error responses
    responses.foreach({
      case e: ErrorResponse => log.warn("Error response details: " + e.msg, e.exn)
      case _ => /* nothing to do */
    })

    responses match {
      case hd :: Nil => hd.getJson.prettyPrint
      case _         => JsArray(responses.map(_.getJson):_*).prettyPrint
    }
  }


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Begin Routing
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  //Some common partials.
  val userPrefix = pathPrefix("user" / Segment)

  //The static directory.
  val staticRoute =
    pathPrefix("") { get {
      respondWithHeader(`Cache-Control`(Seq(`no-cache`, `max-age`(0)))) {
        getFromResourceDirectory("")
      }
    }}
  val homePage = path("") { get {
    respondWithHeader(`Cache-Control`(Seq(`no-cache`, `max-age`(0)))) {
      getFromResource("index_bootstrap.html")
    }
  }}


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Users
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val users = pathPrefix("user" / Segment / Segment) { (username, password) => {
    implicit val sessionUser = None
    pathEnd {
      get {
        val request = new LoginRequest(database,username,password)
        complete(standardCompletion(request, EmptyToken()))
      } ~
      post {
        val request = new CreateUserRequest(database, username, password)
        complete(standardCompletion(request, EmptyToken()))
      }
    }
  }}

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Models
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // FYI to get cookies do this:
  val cookie_echo = pathPrefix("cookie_echo" / Segment) { cookieName => cookie(cookieName) { cookieValue => {
    complete(cookieName + ": " + cookieValue.content)
  }}}

  // GET /models/user returns a list of all models belonging to this user. The cookie must be set.
  val modelList = (t : SessionToken) => pathPrefix("models" / "users" / Segment) {userId => { pathEnd { get {
    val request = new GetModelListRequest(database, userId)
    complete(standardCompletion(request, t))
  }}}}

  //POST /users/<user id>/model/< name >/< keyFile >
  val userModel = (t : SessionToken) => userPrefix {userId => {pathPrefix("model" / Segment) {modelNameOrId => {pathEnd {
    post {
      entity(as[MultipartFormData]) { formData => {
        if(formData.fields.length > 1) ??? //should only have a single file.
        val data = formData.fields.last
        val contents = getFileContentsFromFormData(data)
        val request = new CreateModelRequest(database, userId, modelNameOrId, contents)
        complete(standardCompletion(request, t))
      }}
    } ~
    get {
      val request = new GetModelRequest(database, userId, modelNameOrId)
      complete(standardCompletion(request, t))
    }
  }}}}}

  val deleteModel = (t : SessionToken) => userPrefix {userId => pathPrefix("model" / Segment / "delete") { modelId => pathEnd {
    post {
      val r = new DeleteModelRequest(database, userId, modelId)
      complete(standardCompletion(r, t))
    }
  }}}

  val deleteProof = (t : SessionToken) => userPrefix {userId => pathPrefix("proof" / Segment / "delete") { proofId => pathEnd {
    post {
      val r = new DeleteProofRequest(database, userId, proofId)
      complete(standardCompletion(r, t))
    }
  }}}

  //Because apparently FTP > modern web.
  val userModel2 = (t : SessionToken) => userPrefix {userId => {pathPrefix("modeltextupload" / Segment) {modelNameOrId =>
  {pathEnd {
    post {
      entity(as[String]) { contents => {
        val request = new CreateModelRequest(database, userId, modelNameOrId, contents)
        complete(standardCompletion(request, t))
      }}}}}}}}

  val modelTactic = (t : SessionToken) => path("user" / Segment / "model" / Segment / "tactic") { (userId, modelId) => pathEnd {
    get {
      val request = new GetModelTacticRequest(database, userId, modelId)
      complete(standardCompletion(request, t))
    }
  }}

  val extractTactic = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "extract") { (userId, proofId) => { pathEnd {
    get {
      val request = new ExtractTacticRequest(database, proofId)
      complete(standardCompletion(request, t))
    }
  }}}

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Proofs
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val createProof = (t: SessionToken) => path("models" / "users" / Segment / "model" / Segment / "createProof") { (userId, modelId) => { pathEnd {
    post {
      entity(as[String]) { x => {
        val obj = x.parseJson
        val proofName        = obj.asJsObject.getFields("proofName").last.asInstanceOf[JsString].value
        val proofDescription = obj.asJsObject.getFields("proofDescription").last.asInstanceOf[JsString].value
        val request = new CreateProofRequest(database, userId, modelId, proofName, proofDescription)
        complete(standardCompletion(request, t))
      }}
    }
  }}}

  val proofListForModel = (t: SessionToken) => path("models" / "users" / Segment / "model" / Segment / "proofs") { (userId, modelId) => { pathEnd {
    get {
      val request = new ProofsForModelRequest(database, userId, modelId)
      complete(standardCompletion(request, t))
    }
  }}}

  val proofList = (t: SessionToken) => path("models" / "users" / Segment / "proofs") { (userId) => { pathEnd {
    get {
      val request = new ProofsForUserRequest(database, userId)
      complete(standardCompletion(request, t))
    }
  }}}

  val openProof = (t : SessionToken) => path("proofs" / "user" / Segment / Segment) { (userId, proofId) => { pathEnd {
    get {
      val request = new OpenProofRequest(database, userId, proofId)
      complete(standardCompletion(request, t))
    }
  }}}

  val dashInfo = (t : SessionToken) => path("users" / Segment / "dashinfo") { userId => pathEnd {
    get {
      val request = new DashInfoRequest(database, userId)
      complete(standardCompletion(request, t))
    }
  }}

  val proofTasksNew = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "agendaawesome") { (userId, proofId) => { pathEnd {
    get {
      val request = new GetAgendaAwesomeRequest(database, userId, proofId)
      complete(standardCompletion(request, t))
    }
  }}}

  val proofTasksParent = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "parent") { (userId, proofId, nodeId) => { pathEnd {
    get {
      val request = new ProofTaskParentRequest(database, userId, proofId, nodeId)
      complete(standardCompletion(request, t))
    }
  }}}

  val proofTasksPathAll = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "pathall") { (userId, proofId, nodeId) => { pathEnd {
    get {
      val request = new GetPathAllRequest(database, userId, proofId, nodeId)
      complete(standardCompletion(request, t))
    }
  }}}

  val proofTasksBranchRoot = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "branchroot") { (userId, proofId, nodeId) => { pathEnd {
    get {
      val request = new GetBranchRootRequest(database, userId, proofId, nodeId)
      complete(standardCompletion(request, t))
    }
  }}}

  /* Strictly positive position = SuccPosition, strictly negative = AntePosition, 0 not used */
  def parseFormulaId(id:String): Position = {
    val (idx :: inExprs) = id.split(',').toList.map({case str => str.toInt})
    try { Position(idx, inExprs) }
    catch {
      case e: IllegalArgumentException =>
        throw new Exception("Invalid formulaId " + id + " in axiomList").initCause(e)
    }
  }

  val axiomList = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / "list") { (userId, proofId, nodeId, formulaId) => { pathEnd {
    get {
      val request = new GetApplicableAxiomsRequest(database, userId, proofId, nodeId, parseFormulaId(formulaId))
      complete(standardCompletion(request, t))
    }
  }}}

  val twoPosList = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / Segment / "twoposlist") { (userId, proofId, nodeId, fml1Id, fml2Id) => { pathEnd {
    get {
      val request = new GetApplicableTwoPosTacticsRequest(database, userId, proofId, nodeId, parseFormulaId(fml1Id), parseFormulaId(fml2Id))
      complete(standardCompletion(request, t))
    }
  }}}

  val derivationInfo = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "derivationInfos" / Segment) { (userId, proofId, nodeId, axiomId) => { pathEnd {
    get {
      val request = new GetDerivationInfoRequest(database, userId, proofId, nodeId, axiomId)
      complete(standardCompletion(request, t))
    }
  }}}

  val doAt = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / "doAt" / Segment) { (userId, proofId, nodeId, formulaId, tacticId) => { pathEnd {
    get {
      val request = new RunBelleTermRequest(database, userId, proofId, nodeId, tacticId, Some(Fixed(parseFormulaId(formulaId))))
      complete(standardCompletion(request, t))
    }}
  }}

  val doInputAt = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / "doInputAt" / Segment) { (userId, proofId, nodeId, formulaId, tacticId) => { pathEnd {
    post {
      entity(as[String]) { params => {
        val info = DerivationInfo(tacticId)
        val expectedInputs = info.inputs
        // Input has format [{"type":"formula","param":"j(x)","value":"v >= 0"}]
        val paramArray = JsonParser(params).asInstanceOf[JsArray]
        val inputs =
          paramArray.elements.map({case elem =>
            val obj = elem.asJsObject()
            val paramName = obj.getFields("param").head.asInstanceOf[JsString].value
            val paramValue = obj.getFields("value").head.asInstanceOf[JsString].value
            val paramInfo = expectedInputs.find{case spec => spec.name == paramName}
            BelleTermInput(paramValue, paramInfo)
          })
        val request = new RunBelleTermRequest(database, userId, proofId, nodeId, tacticId, Some(Fixed(parseFormulaId(formulaId))), None, inputs.toList)
        complete(standardCompletion(request, t))
      }
    }}
  }}}

  val doTwoPosAt = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / Segment / "doAt" / Segment) { (userId, proofId, nodeId, fml1Id, fml2Id, tacticId) => { pathEnd {
    get {
      val request = new RunBelleTermRequest(database, userId, proofId, nodeId, tacticId,
        Some(Fixed(parseFormulaId(fml1Id))), Some(Fixed(parseFormulaId(fml2Id))))
      complete(standardCompletion(request, t))
    }}
  }}

  val doTactic = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "do" / Segment) { (userId, proofId, nodeId, tacticId) => { pathEnd {
    get {
      val request = new RunBelleTermRequest(database, userId, proofId, nodeId, tacticId, None)
      complete(standardCompletion(request, t))
    }}
  }}

  val doInputTactic = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "doInput" / Segment) { (userId, proofId, nodeId, tacticId) => { pathEnd {
    post {
      entity(as[String]) { params => {
        val info = DerivationInfo(tacticId)
        val expectedInputs = info.inputs
        // Input has format [{"type":"formula","param":"j(x)","value":"v >= 0"}]
        val paramArray = JsonParser(params).asInstanceOf[JsArray]
        val inputs =
          paramArray.elements.map({case elem =>
            val obj = elem.asJsObject()
            val paramName = obj.getFields("param").head.asInstanceOf[JsString].value
            val paramValue = obj.getFields("value").head.asInstanceOf[JsString].value
            val paramInfo = expectedInputs.find{case spec => spec.name == paramName}
            BelleTermInput(paramValue, paramInfo)
          })
        val request = new RunBelleTermRequest(database, userId, proofId, nodeId, tacticId, None, None, inputs.toList)
        complete(standardCompletion(request, t))
      }
      }}
  }}}

  val doCustomTactic = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "doCustomTactic") { (userId, proofId, nodeId) => { pathEnd {
    post {
      entity(as[String]) { tactic => {
        val request = new RunBelleTermRequest(database, userId, proofId, nodeId, tactic, None, consultAxiomInfo=false)
        complete(standardCompletion(request, t))
      }}
    }}
  }}

  val doSearch = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "doSearch" / Segment / Segment) { (userId, proofId, goalId, where, tacticId) => { pathEnd {
    get {
      val pos = where match {
        case "R" => Find.FindR(0, None)
        case "L" => Find.FindL(0, None)
        case loc => throw new IllegalArgumentException("Unknown position locator " + loc)
      }
      val request = new RunBelleTermRequest(database, userId, proofId, goalId, tacticId, Some(pos))
      complete(standardCompletion(request, t))
    } ~
    post {
      entity(as[String]) { params => {
        val info = DerivationInfo(tacticId)
        val expectedInputs = info.inputs
        // Input has format [{"type":"formula","param":"j(x)","value":"v >= 0"}]
        val paramArray = JsonParser(params).asInstanceOf[JsArray]
        val inputs =
          paramArray.elements.map({case elem =>
            val obj = elem.asJsObject()
            val paramName = obj.getFields("param").head.asInstanceOf[JsString].value
            val paramValue = obj.getFields("value").head.asInstanceOf[JsString].value
            val paramInfo = expectedInputs.find{case spec => spec.name == paramName}
            BelleTermInput(paramValue, paramInfo)
          })
        val pos = where match {
          case "R" => Find.FindR(0, None)
          case "L" => Find.FindL(0, None)
          case loc => throw new IllegalArgumentException("Unknown position locator " + loc)
        }
        val request = new RunBelleTermRequest(database, userId, proofId, goalId, tacticId, Some(pos), None, inputs.toList)
        complete(standardCompletion(request, t))
      }
      }}
    }
  }}

  val taskStatus = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / "status") { (userId, proofId, nodeId, taskId) => { pathEnd {
    get {
      val request = new TaskStatusRequest(database, userId, proofId, nodeId, taskId)
      complete(standardCompletion(request, t))
    }}
  }}

  val taskResult = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / "result") { (userId, proofId, nodeId, taskId) => { pathEnd {
    get {
      val request = new TaskResultRequest(database, userId, proofId, nodeId, taskId)
      complete(standardCompletion(request, t))
    }}
  }}

  val stopTask = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / Segment / "stop") { (userId, proofId, nodeId, taskId) => { pathEnd {
    get {
      val request = new StopTaskRequest(database, userId, proofId, nodeId, taskId)
      complete(standardCompletion(request, t))
    }}
  }}

  val pruneBelow = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "pruneBelow") { (userId, proofId, nodeId) => { pathEnd {
    get {
      val request = new PruneBelowRequest(database, userId, proofId, nodeId)
      complete(standardCompletion(request, t))
    }
  }}}

  val getAgendaItem = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "agendaItem" / Segment) { (userId, proofId, nodeId) => { pathEnd {
    get {
      val request = new GetAgendaItemRequest(database, userId, proofId, nodeId)
      complete(standardCompletion(request, t))
    }}}}

  val setAgendaItemName = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "name" / Segment) { (userId, proofId, nodeId, newName) => { pathEnd {
    post {
      entity(as[String]) { params => {
        val request = new SetAgendaItemNameRequest(database, userId, proofId, nodeId, newName)
        complete(standardCompletion(request, t))
    }}}}}}

  val proofProgressStatus = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "progress") { (userId, proofId) => { pathEnd {
    get {
      val request = new GetProofProgressStatusRequest(database, userId, proofId)
      complete(standardCompletion(request, t))
    }
  }}}

  val proofCheckIsProved = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "validatedStatus") { (userId, proofId) => { pathEnd {
    get {
      val request = new CheckIsProvedRequest(database, userId, proofId)
      complete(standardCompletion(request, t))
    }
  }}}

  val counterExample = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "counterExample") { (userId, proofId, nodeId) => {
    pathEnd {
      get {
        val request = new CounterExampleRequest(database, userId, proofId, nodeId)
        complete(standardCompletion(request, t))
      }
    }}
  }

  val setupSimulation = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "setupSimulation") { (userId, proofId, nodeId) => {
    pathEnd {
      get {
        val request = new SetupSimulationRequest(database, userId, proofId, nodeId)
        complete(standardCompletion(request, t))
      }
    }}
  }

  val simulate = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / Segment / "simulate") { (userId, proofId, nodeId) => {
    pathEnd {
      post {
        entity(as[String]) { params => {
          val obj = JsonParser(params).asJsObject()
          val initial = obj.fields("initial").asInstanceOf[JsString].value.asFormula
          val stateRelation = obj.fields("stateRelation").asInstanceOf[JsString].value.asFormula
          val numSteps = obj.fields("numSteps").asInstanceOf[JsNumber].value.intValue()
          val stepDuration = obj.fields("stepDuration").asInstanceOf[JsString].value.asTerm
          val request = new SimulationRequest(database, userId, proofId, nodeId, initial, stateRelation, numSteps, 1, stepDuration)
          complete(standardCompletion(request, t))
        }}}
    }}
  }

  val kyxConfig = path("kyxConfig") {
    pathEnd {
      get {
        val request = new KyxConfigRequest(database)
        complete(standardCompletion(request, EmptyToken()))
      }
    }
  }

  val keymaeraXVersion = path("keymaeraXVersion") {
    pathEnd {
      get {
        val request = new KeymaeraXVersionRequest()
        complete(standardCompletion(request, EmptyToken()))
      }
    }
  }

  val mathConfSuggestion = path("config" / "mathematica" / "suggest") {
    pathEnd {
      get {
        val request = new GetMathematicaConfigSuggestionRequest(database)
        complete(standardCompletion(request, EmptyToken()))
      }
    }
  }

  val mathematicaConfig = path("config" / "mathematica") {
    pathEnd {
      get {
          val request = new GetMathematicaConfigurationRequest(database)
          complete(standardCompletion(request, EmptyToken()))
      } ~
      post {
        entity(as[String]) { params => {
          val p = JsonParser(params).asJsObject.fields.map(param => param._1.toString -> param._2.asInstanceOf[JsString].value)
          assert(p.contains("linkName"), "linkName not in: " + p.keys.toString())
          assert(p.contains("jlinkLibDir"), "jlinkLibDir not in: " + p.keys.toString()) //@todo These are schema violations and should be checked as such, but I needed to disable the validator.
          val linkName : String = p("linkName")
          val jlinkLibDir : String = p("jlinkLibDir")
          val request = new ConfigureMathematicaRequest(database, linkName, jlinkLibDir)
          complete(standardCompletion(request, EmptyToken()))
        }}
      }
    }
  }

  val mathematicaStatus = path("config" / "mathematicaStatus") {
    pathEnd {
      get {
        complete(standardCompletion(new MathematicaStatusRequest(database), EmptyToken()))
      }
    }
  }

  val runBelleTerm = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "nodes" / Segment / "tactics" / "runBelleTerm") { (userId, proofId, nodeId) => { pathEnd {
    post {
      entity(as[String]) { params => {
        val term = JsonParser(params).asJsObject.fields.last._2.asInstanceOf[JsString].value
        val request = new RunBelleTermRequest(database, userId, proofId, nodeId, term, None)
        complete(standardCompletion(request, t))
  }}}}}}

  val changeProofName = (t : SessionToken) => path("proofs" / "user" / Segment / Segment / "name" / Segment) { (userId, proofId, newName) => { pathEnd {
    post {
      entity(as[String]) { params => {
        complete(standardCompletion(new UpdateProofNameRequest(database, userId, proofId, newName), t))
      }}
    }
  }}}

  val devAction = path("dev" / Segment) { (action) => {
    get {
      assert(!Boot.isHosted, "dev actions are only available on locally hosted instances.")
      if(action.equals("deletedb")) {
        database.cleanup()
        complete("{}")
      }
      else {
        complete("{}")
      }
    }
  }}

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Server management
  //////////////////////////////////////////////////////////////////////////////////////////////////

  val isLocal = path("isLocal") { pathEnd { get {
    implicit val sessionUser = None
    complete(standardCompletion(new IsLocalInstanceRequest(), EmptyToken()))
  }}}

  val shutdown = path("shutdown") { pathEnd { get {
    implicit val sessionUser = None
    complete(standardCompletion(new ShutdownReqeuest(), EmptyToken()))
  }}}

  val extractdb = path("extractdb") { pathEnd { post {
    implicit val sessionUser = None
    complete(standardCompletion(new ExtractDatabaseRequest(), EmptyToken()))
  }}}


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Licensing
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val license = path("licenseacceptance") {  {
    get {
      complete(standardCompletion(new IsLicenseAcceptedRequest(database), EmptyToken()))
    } ~
    post {
      complete(standardCompletion(new AcceptLicenseRequest(database), EmptyToken()))
    }
  }}

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Route precedence
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val publicRoutes =
    staticRoute        ::
    homePage           ::
    license            ::
    isLocal            ::
    extractdb          ::
    shutdown           ::
    users              ::
    cookie_echo        ::
    kyxConfig          ::
    keymaeraXVersion   ::
    mathematicaConfig  ::
    mathematicaStatus  ::
    mathConfSuggestion ::
    devAction          ::
    Nil

  /** Requests that need a session token parameter.
    *
    * @see [[sessionRoutes]] is built by wrapping all of these sessions in a cookieOptional("session") {...} that extrtacts the cookie name. */
  private val partialSessionRoutes : List[SessionToken => routing.Route] =
    modelList             ::
    modelTactic           ::
    userModel             ::
    userModel2            ::
    deleteModel           ::
    createProof           ::
    deleteProof           ::
    proofListForModel     ::
    proofList             ::
    openProof             ::
    getAgendaItem         ::
    setAgendaItemName     ::
    changeProofName       ::
    proofProgressStatus   ::
    proofCheckIsProved    ::
    proofTasksNew         ::
    proofTasksParent      ::
    proofTasksPathAll     ::
    proofTasksBranchRoot  ::
    axiomList             ::
    twoPosList            ::
    derivationInfo        ::
    doAt                  ::
    doTwoPosAt            ::
    doInputAt             ::
    doTactic              ::
    doInputTactic         ::
    doCustomTactic        ::
    doSearch              ::
    taskStatus            ::
    taskResult            ::
    stopTask              ::
    extractTactic         ::
    counterExample        ::
    setupSimulation       ::
    simulate              ::
    pruneBelow            ::
    dashInfo              ::
    Nil

  val sessionRoutes : List[routing.Route] = partialSessionRoutes.map(routeForSession => optionalHeaderValueByName("x-session-token") {
    case Some(token) => routeForSession(SessionManager.token(token))
    case None => routeForSession(EmptyToken())
  })

  val myRoute = (publicRoutes ++ sessionRoutes).reduce(_ ~ _)
}


//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// In-memory session managements @todo replace with something less naive.
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
  * @todo replace this with an existing library that does The Right Things
  * @todo do we want to enforce timeouts as well?
  */
object SessionManager {
  private var sessionMap : Map[String, (String, Date)] = Map() //Session tokens -> usernames

  def token(key: String): SessionToken = sessionMap.get(key) match {
    case Some((username, timeout)) => {
      if(new Date().before(timeout)) {
        UsedToken(key, username)
      }
      else {
        remove(key);
        NewlyExpiredToken(key)
      }
    }
    case None => EmptyToken()
  }

  def add(username: String): String = {
    val sessionToken = generateToken() //@todo generate a proper key.
    sessionMap = sessionMap + (sessionToken -> (username, timeoutDate))
    sessionToken
  }

  def remove(key: String): Unit = {
    sessionMap = sessionMap.filter(p => p._1 != key)
  }

  private def timeoutDate : Date = {
    val c = Calendar.getInstance()
    c.add(Calendar.DATE, 7)
    c.getTime
  }

  private def generateToken(): String = {
    val random: SecureRandom = new SecureRandom();
    val bytes = Array[Byte](20)
    random.nextBytes(bytes);
    val candidate = bytes.toString();
    if(sessionMap.contains(candidate)) generateToken()
    else candidate
  }
}

/** @note a custom Option so that Scala doesn't use None as an implicit parameter. */
trait SessionToken {
  def isLoggedIn = this.isInstanceOf[UsedToken]

  def belongsTo(uname: String) = this match {
    case UsedToken(t,u) => u == uname
    case x:EmptyToken => false
  }
}
case class UsedToken(token: String, username: String) extends SessionToken
case class NewlyExpiredToken(token: String) extends SessionToken
case class EmptyToken() extends SessionToken