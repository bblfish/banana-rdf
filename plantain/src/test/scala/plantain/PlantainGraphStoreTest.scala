package org.w3.banana.plantain

import org.w3.banana._
import org.scalatest._
import org.scalatest.matchers._
import Plantain._
import LDPCommand._
import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.charset.Charset
import java.nio.file.{Files, Paths, Path}
import java.io.File
import play.api.libs.iteratee.Enumerator
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import scalaz.Validation
import collection.immutable
import scalaz.Free.{Gosub, Suspend, Return}
import annotation.tailrec

class PlantainLDPSTest extends LDPSTest[Plantain]({
  val dir = Files.createTempDirectory("plantain")
  PlantainLDPS(URI.fromString("http://example.com/foo"), dir)
})

abstract class LDPSTest[Rdf <: RDF](
  ldps: LDPS[Rdf])(
  implicit diesel: Diesel[Rdf],
  reader: RDFReader[Rdf, RDFXML]) extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import System.out

  import diesel._
  import ops._

  val foaf = FOAFPrefix[Rdf]
  val wac = WebACL[Rdf]

  val betehess = URI("http://example.com/betehess/card#me")
  val betehessCard = URI("http://example.com/betehess/card")

  override def afterAll(): Unit = {
    ldps.shutdown()
  }

  val graph: Rdf#Graph = (
    URI("#me")
    -- foaf.name ->- "Alexandre".lang("fr")
    -- foaf.title ->- "Mr"
  ).graph

  // make the card readable by the whole world ( and link to the main file that make it read/write to Alex )
  val graphCardACL: Rdf#Graph = (
    bnode()
      -- wac.accessTo ->- betehessCard
      -- wac.agentClass ->- foaf.Agent
      -- wac.mode     ->- wac.Read
    ).graph union (
      URI(betehessCard.toString+";meta") -- wac.include ->- URI("http://example.com/betehess/;meta")
    ).graph

  // this makes all of the files under the betehess collection read/write to an Alex
  val graphCollectionACL = (
    bnode()
       -- wac.accessToClass ->- ( bnode() -- wac.regex ->- "http://example.com/betehess/.*" )
       -- wac.accessTo ->- betehessCard
       -- wac.agent ->-  betehess
       -- wac.mode  ->- (wac.Read, wac.Write)
  ).graph

  val graph2: Rdf#Graph = (
    URI("#me")
    -- foaf.name ->- "Alexandre".lang("fr")
    -- foaf.knows ->- (
      URI("http://bblfish.net/#hjs")
      -- foaf.name ->- "Henry Story"
      -- foaf.currentProject ->- URI("http://webid.info/")
    )
  ).graph

  val foo: Rdf#Graph = (
    URI("http://example.com/foo")
    -- rdf("foo") ->- "foo"
    -- rdf("bar") ->- "bar"
  ).graph

  val helloWorldBinary = "☯ Hello, World! ☮".getBytes("UTF-8")

  val helloWorldBinary2 = "Hello, World!".getBytes("UTF-8")

  "CreateLDPR should create an LDPR with the given graph -- with given uri" in {
    val ldpcUri = URI("http://example.com/foo1")
    val ldprUri = URI("http://example.com/foo1/betehess")
    val script = for {
      ldpc <- ldps.createLDPC(ldpcUri)
      rUri <- ldpc.execute(createLDPR(Some(ldprUri), graph))
      rGraph <- ldpc.execute(getLDPR(ldprUri))
      _ <- ldps.deleteLDPC(ldpcUri)
    } yield {
      rUri must be(ldprUri)
      assert(rGraph isIsomorphicWith graph)
    }
    script.getOrFail()
  }

  "CreateLDPR should create an LDPR with the given graph -- no given uri" in {
    val ldpcUri = URI("http://example.com/foo2")
    val script = for {
      ldpc <- ldps.createLDPC(ldpcUri)
      rUri <- ldpc.execute(createLDPR(None, graph))
      rGraph <- ldpc.execute(getLDPR(rUri))
      _ <- ldps.deleteLDPC(ldpcUri)
    } yield {
      rUri.relativizeAgainst(ldpcUri).toString must not include ("/")
      assert(rGraph isIsomorphicWith graph)
    }
    script.getOrFail()
  }

  trait Agent {
    def contains(id: Rdf#URI): Boolean
  }

  object Agent extends Agent {
    //ok really the id should represent an Agent, and not say a stone
    def contains(id: Rdf#URI) = true
  }

  case class Group(members: List[Rdf#URI]) extends Agent {
    override
    def contains(id: Rdf#URI): Boolean = members.contains(id)
  }

  case class Person(id: Rdf#URI) extends Agent {
    override
    def contains(webid: Rdf#URI) = id == webid
  }


  /**
   * return the list of agents that are allowed access to the given resource
   * stop looking if everybody is authorized
   * @return A list of Agents with access ( should be perhaps just an Agent.
   **/
  def authz(acl: Rdf#Graph, resource: Rdf#URI, method: Rdf#URI): List[Agent]  = {
    def agent(a: PointedGraph[Rdf]): Agent = if (a.pointer == foaf.Agent) Agent
     else {
       val people = (a/foaf.member).collect{ case PointedGraph(p,_) if isURI(p)  => p.asInstanceOf[Rdf#URI]}.toList
       Group(people)
   }

    def authorized(auths: Iterator[PointedGraph[Rdf]]): List[Agent] =
      if (!auths.hasNext) {
         List()
      } else {
        val az = auths.next
        val ac = (az / wac.agentClass).map { agent _ }.toList
        if (ac.contains(foaf.Agent))  List(Agent)
        else {
          (az/wac.agent).collect { case PointedGraph(p,_) if isURI(p) => p.asInstanceOf[Rdf#URI]}.toList match {
            case Nil => ac
            case list: List[Rdf#URI] => Group(list)::ac:::authorized(auths)
          }
        }
      }

    val it = (PointedGraph(method,acl)/-wac.mode)
    val result = authorized(it.iterator)
    //compress result
    if (result.contains(Agent)) List(Agent)
    else result
  }



  "CreateLDPC & LDPR with ACLs" in {
    val ldpcUri = URI("http://example.com/betehess/")
    val ldpcMetaFull = URI("http://example.com/betehess/;meta")
    val ldprUri = URI("card")
    val ldprUriFull = betehessCard
    val ldprMeta = URI("http://example.com/betehess/card;meta")

    //create container with ACLs
    val createContainerScript = for {
      ldpc <- ldps.createLDPC(ldpcUri)
      rUri <- ldpc.execute(createLDPR(ldpc.acl, graphCollectionACL))
      acl <- ldpc.execute(getLDPR(rUri))
    } yield {
      rUri must be(ldpcMetaFull)
      assert(acl isIsomorphicWith graphCollectionACL)
    }
    createContainerScript.getOrFail()

    //create Profile with ACLs
    val createProfile = for {
      ldpc <- ldps.getLDPC(ldpcUri)
      cardRes <- ldpc.execute(
        for {
//        rUri <- createLDPR(Some(ldprUri), graph) <- should something like this work?
          rUri <- createLDPR(Some(ldprUriFull), graph)
          res  <- getResource(rUri)
          _    <- createLDPR( res.acl, graphCardACL )
        } yield res  )
      acl <- ldpc.execute(getLDPR(cardRes.acl.get))
    } yield {
      cardRes.uri must be(ldprUriFull)
      cardRes.acl.get must be(ldprMeta)
      assert(acl.resolveAgainst(ldprMeta) isIsomorphicWith graphCardACL)
      cardRes match {
        case card: LDPR[Rdf] => assert( card.graph isIsomorphicWith graph.resolveAgainst(ldprUriFull))
        case _ => throw new Exception("recived the wrong type of resource")
      }
    }
    createProfile.getOrFail()

    /* recursive function, gets included auths in a collection (only) - but is not tail rec because of flatMap */
    def getAuth(meta: Rdf#URI, method: Rdf#URI): Script[Rdf, List[Agent]] =  {
      getLDPR(meta).flatMap { g: Rdf#Graph =>
        val az = authz(g, ldprUriFull, method)
        az match {
          case List(Agent) => Return[({ type l[+x] = LDPCommand[Rdf, x] })#l, List[Agent]](az)
          case agents => {
            val inc= (PointedGraph(URI(""), g) / wac.include).collectFirst { //todo: check that its' in the collection. What to do if it's not?
              case PointedGraph(node,g) if isURI(node) =>
                getAuth(node.asInstanceOf[Rdf#URI],method)
            }
            val res = inc.getOrElse(Return[({ type l[+x] = LDPCommand[Rdf, x] })#l, List[Agent]](az))
            res
          }
        }
      }
    }


    val authZ1 = for {
      ldpc <- ldps.getLDPC(ldpcUri)
      l <- ldpc.execute (
       for {
         meta <- getMeta(ldprUriFull)
         athzd <- getAuth(meta.acl.get,wac.Read)
       } yield athzd
      )
    } yield {
       assert( l.contains(Agent) )
    }

    authZ1.getOrFail()

    val authZ2 = for {
      ldpc <- ldps.getLDPC(ldpcUri)
      l <- ldpc.execute (
        for {
          meta <- getMeta(ldprUriFull)
          athzd <- getAuth(meta.acl.get,wac.Write)
        } yield athzd
      )
    } yield {
      assert( l.exists(a => a.contains(betehess) ))
    }

    authZ2.getOrFail()


    //    rAcl <- ldpc.execute{
//      for {
//        meta <- getMeta(ldprUri)
//        acl = meta.acl.get
//        _   <- updateLDPR(acl,Iterable.empty,graphMeta.toIterable)
//      } yield acl
//    }

   //add access control tests here on the graph created above


  }

  "Create Binary" in {
    val ldpcUri = URI("http://example.com/foocb")
    val binUri = URI("http://example.com/foocb/img.jpg")
    val ldprMeta = URI("http://example.com/foocb/img.jpg;meta")

    val createBin = for {
      ldpc <- ldps.createLDPC(ldpcUri)
      bin <- ldpc.execute(createBinary(Some(binUri)))
      it = bin.write
      newbin <- Enumerator(helloWorldBinary).apply(it)
      newres <- newbin.run
    } yield {
      bin.uri must be(binUri)
      newres.uri must be(binUri)
    }
    createBin.getOrFail()

    def getBin(hw: Array[Byte]) = for {
       ldpc <- ldps.getLDPC(ldpcUri)
       res  <- ldpc.execute(getResource(binUri))
    } yield {
      res match {
        case bin: BinaryResource[Rdf] => bin.reader(400).map{ bytes =>
          hw must be(bytes)
        }
        case _ => throw new Exception("Object MUST be a binary - given that this test is not running in an open world")
      }

    }

    getBin(helloWorldBinary).getOrFail()

    val editBin = for {
      ldpc <- ldps.getLDPC(ldpcUri)
      newRes <- ldpc.execute(getResource(binUri)) // we get the resource, but we don't use that thread to upload the data
      bin <- newRes match { //rather here we should use the client thread to upload the data ( as it could be very large )
        case br: BinaryResource[Rdf] => for {
          it <- Enumerator(helloWorldBinary2) |>> br.write
          newres <- it.run
        } yield newres
        case _ => throw new Exception("Object MUST be binary - given that this test is not running in an open world")
      }
    } yield {
      bin.uri must be(binUri)
    }
    editBin.getOrFail()

    getBin(helloWorldBinary2).getOrFail()

    val deleteBin = for {
      ldpc <- ldps.getLDPC(ldpcUri)
      _ <- ldpc.execute(deleteResource(binUri))
      _ <- ldpc.execute(getResource(binUri))
    } yield {
      "hello"
    }

    val res = Await.result(deleteBin.failed,Duration(1,TimeUnit.SECONDS))
    assert(res.isInstanceOf[NoSuchElementException])

  }


  "appendToGraph should be equivalent to graph union" in {
    val ldpcUri = URI("http://example.com/foo3")
    val ldprUri = URI("http://example.com/foo3/betehess")
    val script = for {
      ldpc <- ldps.createLDPC(ldpcUri)
      rUri <- ldpc.execute(createLDPR(Some(ldprUri), graph))
    } yield {
      rUri must be(ldprUri)
    }
    script.getOrFail()

    val script2 = for {
      ldpc <- ldps.getLDPC(ldpcUri)
      unionG <- ldpc.execute(updateLDPR(ldprUri, Iterable.empty, graph2.toIterable).flatMap { _ =>
        getLDPR(ldprUri)
      })
    } yield {
      assert( unionG isIsomorphicWith( graph union graph2) )
    }

    script2.getOrFail()

  }

  "access control test" in {


  }

//  "patchGraph should delete and insert triples as expected" in {
//    val ldpcUri = URI("http://example.com/foo4")
//    val ldprUri = URI("http://example.com/foo4/betehess")
//todo: need to add PATCH mechanism
//    val r = for {
//      _ <- graphStore.removeGraph(u)
//      _ <- graphStore.appendToGraph(u, foo)
//      _ <- graphStore.patchGraph(u,
//        (URI("http://example.com/foo") -- rdf("foo") ->- "foo").graph.toIterable,
//        (URI("http://example.com/foo") -- rdf("baz") ->- "baz").graph)
//      rGraph <- graphStore.getGraph(u)
//    } yield {
//      val expected = (
//        URI("http://example.com/foo")
//        -- rdf("bar") ->- "bar"
//        -- rdf("baz") ->- "baz"
//      ).graph
//      assert(rGraph isIsomorphicWith expected)
//    }
//    r.getOrFail()
//  }

}
