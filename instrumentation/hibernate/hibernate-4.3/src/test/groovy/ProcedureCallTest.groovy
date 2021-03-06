/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import io.opentelemetry.auto.instrumentation.api.MoreTags
import io.opentelemetry.auto.instrumentation.api.SpanTypes
import io.opentelemetry.auto.instrumentation.api.Tags
import io.opentelemetry.auto.test.AgentTestRunner
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.hibernate.exception.SQLGrammarException
import org.hibernate.procedure.ProcedureCall
import spock.lang.Shared

import javax.persistence.ParameterMode
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

import static io.opentelemetry.trace.Span.Kind.CLIENT
import static io.opentelemetry.trace.Span.Kind.INTERNAL

class ProcedureCallTest extends AgentTestRunner {


  @Shared
  protected SessionFactory sessionFactory

  @Shared
  protected List<Value> prepopulated

  def setupSpec() {
    sessionFactory = new Configuration().configure().buildSessionFactory()
    // Pre-populate the DB, so delete/update can be tested.
    Session writer = sessionFactory.openSession()
    writer.beginTransaction()
    prepopulated = new ArrayList<>()
    for (int i = 0; i < 2; i++) {
      prepopulated.add(new Value("Hello :) " + i))
      writer.save(prepopulated.get(i))
    }
    writer.getTransaction().commit()
    writer.close()

    // Create a stored procedure.
    Connection conn = DriverManager.getConnection("jdbc:hsqldb:mem:test", "sa", "1")
    Statement stmt = conn.createStatement()
    stmt.execute("CREATE PROCEDURE TEST_PROC() MODIFIES SQL DATA BEGIN ATOMIC INSERT INTO Value VALUES (420, 'fred'); END")
    stmt.close()
    conn.close()
  }

  def cleanupSpec() {
    if (sessionFactory != null) {
      sessionFactory.close()
    }
  }

  def "test ProcedureCall"() {
    setup:

    Session session = sessionFactory.openSession()
    session.beginTransaction()

    ProcedureCall call = session.createStoredProcedureCall("TEST_PROC")
    call.getOutputs()

    session.getTransaction().commit()
    session.close()

    expect:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          operationName "hibernate.session"
          spanKind INTERNAL
          parent()
          tags {
            "$MoreTags.SERVICE_NAME" "hibernate"
            "$MoreTags.SPAN_TYPE" SpanTypes.HIBERNATE
            "$Tags.COMPONENT" "java-hibernate"
          }
        }
        span(1) {
          operationName "hibernate.procedure.getOutputs"
          spanKind INTERNAL
          childOf span(0)
          tags {
            "$MoreTags.SERVICE_NAME" "hibernate"
            "$MoreTags.RESOURCE_NAME" "TEST_PROC"
            "$MoreTags.SPAN_TYPE" SpanTypes.HIBERNATE
            "$Tags.COMPONENT" "java-hibernate"
          }
        }
        span(2) {
          operationName "{call TEST_PROC()}"
          spanKind CLIENT
          childOf span(1)
          tags {
            "$MoreTags.SERVICE_NAME" "hsqldb"
            "$MoreTags.SPAN_TYPE" "sql"
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.DB_TYPE" "hsqldb"
            "$Tags.DB_INSTANCE" "test"
            "$Tags.DB_USER" "sa"
            "$Tags.DB_STATEMENT" "{call TEST_PROC()}"
            "span.origin.type" "org.hsqldb.jdbc.JDBCCallableStatement"
          }
        }
        span(3) {
          spanKind INTERNAL
          operationName "hibernate.transaction.commit"
          childOf span(0)
          tags {
            "$MoreTags.SERVICE_NAME" "hibernate"
            "$MoreTags.SPAN_TYPE" SpanTypes.HIBERNATE
            "$Tags.COMPONENT" "java-hibernate"
          }
        }
      }
    }
  }

  def "test failing ProcedureCall"() {
    setup:

    Session session = sessionFactory.openSession()
    session.beginTransaction()

    ProcedureCall call = session.createStoredProcedureCall("TEST_PROC")
    call.registerParameter("nonexistent", Long, ParameterMode.IN)
    call.getParameterRegistration("nonexistent").bindValue(420L)
    try {
      call.getOutputs()
    } catch (Exception e) {
      // We expected this.
    }

    session.getTransaction().commit()
    session.close()

    expect:
    assertTraces(1) {
      trace(0, 3) {
        span(0) {
          operationName "hibernate.session"
          spanKind INTERNAL
          parent()
          tags {
            "$MoreTags.SERVICE_NAME" "hibernate"
            "$MoreTags.SPAN_TYPE" SpanTypes.HIBERNATE
            "$Tags.COMPONENT" "java-hibernate"
          }
        }
        span(1) {
          operationName "hibernate.procedure.getOutputs"
          spanKind INTERNAL
          childOf span(0)
          errored(true)
          tags {
            "$MoreTags.SERVICE_NAME" "hibernate"
            "$MoreTags.RESOURCE_NAME" "TEST_PROC"
            "$MoreTags.SPAN_TYPE" SpanTypes.HIBERNATE
            "$Tags.COMPONENT" "java-hibernate"
            errorTags(SQLGrammarException, "could not prepare statement")
          }
        }
        span(2) {
          operationName "hibernate.transaction.commit"
          spanKind INTERNAL
          childOf span(0)
          tags {
            "$MoreTags.SERVICE_NAME" "hibernate"
            "$MoreTags.SPAN_TYPE" SpanTypes.HIBERNATE
            "$Tags.COMPONENT" "java-hibernate"
          }
        }
      }
    }
  }
}

