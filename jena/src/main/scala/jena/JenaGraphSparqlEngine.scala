package org.w3.banana.jena

import org.w3.banana._
import com.hp.hpl.jena.graph.{ Graph => JenaGraph }
import com.hp.hpl.jena.rdf.model._
import com.hp.hpl.jena.query._
import scalaz.Id._

object JenaSparqlGraph extends SparqlGraph[Jena] {

  def apply(graph: Jena#Graph): SparqlEngine[Jena, Id] = new SparqlEngine[Jena, Id] {

    val querySolution = util.QuerySolution()

    def qexec(query: Jena#Query, bindings: Map[String, Jena#Node]): QueryExecution = {
      val model: Model = ModelFactory.createModelForGraph(graph.jenaGraph)
      if (bindings.isEmpty)
        QueryExecutionFactory.create(query, model)
      else
        QueryExecutionFactory.create(query, model, querySolution.getMap(bindings))
    }

    def executeSelect(query: Jena#SelectQuery, bindings: Map[String, Jena#Node]): Jena#Solutions = {
      qexec(query, bindings).execSelect()
    }

    def executeConstruct(query: Jena#ConstructQuery, bindings: Map[String, Jena#Node]): Jena#Graph = {
      val result = qexec(query, bindings).execConstruct()
      BareJenaGraph(result.getGraph())
    }

    def executeAsk(query: Jena#AskQuery, bindings: Map[String, Jena#Node]): Boolean = {
      qexec(query, bindings).execAsk()
    }
  }

}
