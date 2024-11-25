package io.joern.console.cpgcreation

import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.semanticcpg.language.*

import java.io.{File, PrintWriter}
import scala.collection.JavaConverters.*
import java.nio.file.{Files, Paths}

object GenerateUnitTests {
  def analyzeMethods(cpg: Cpg): List[Method] = {
    cpg.method.l.toList
  }

  def generateUnitTest(method: Method, testFramework: String): String = {
    val methodName = method.name
    val className = methodName.capitalize + "Test"

    testFramework match {
      case "ScalaTest" =>
        s"""
      import org.scalatest.flatspec.AnyFlatSpec

      class $className extends AnyFlatSpec {
        "The $methodName method" should "perform expected behavior" in {
          // TODO: Add test logic here
        }
      }
      """
      case "JUnit" =>
        s"""
      import org.junit.Test
      import org.junit.Assert._

      class $className {
        @Test
        def test$methodName(): Unit = {
          // TODO: Add test logic here
        }
      }
      """
      case _ =>
        throw new IllegalArgumentException("Unsupported test framework")
    }
  }

  def createTestFile(testCode: String, methodName: String): Unit = {
    val filePath = s"src/test/scala/${methodName.capitalize}Test.scala"
    val file = new File(filePath)

    if (!file.exists()) {
      val writer = new PrintWriter(file)
      writer.write(testCode)
      writer.close()
    } else {
      println(s"Test file for method $methodName already exists.")
    }
  }

  def detectTestFramework(cpg: Cpg): String = {
    val libraries = cpg.metaData.l.flatMap(metaData => metaData.property("libraries")).map(_.toString.toLowerCase)

    if (libraries.exists(_.contains("scalatest"))) {
      "ScalaTest"
    } else if (libraries.exists(_.contains("junit"))) {
      "JUnit"
    } else {
      throw new RuntimeException("No suitable test framework found")
    }
  }


  def generateTestsForCpg(cpg: Cpg): Unit = {
    val testFramework = detectTestFramework(cpg)
    val methods = analyzeMethods(cpg)

    methods.foreach { method =>
      val testCode = generateUnitTest(method, testFramework)
      createTestFile(testCode, method.name)
    }
  }
}