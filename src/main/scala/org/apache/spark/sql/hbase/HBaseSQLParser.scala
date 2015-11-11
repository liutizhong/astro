/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.hbase

import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.SqlParser
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAttribute, UnresolvedRelation}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.hbase.execution._
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

object HBaseSQLParser {
  def getKeywords: Seq[String] = {
    val hbaseSqlFields =
      Class.forName("org.apache.spark.sql.hbase.HBaseSQLParser").getDeclaredFields
    val sparkSqlFields = Class.forName("org.apache.spark.sql.catalyst.SqlParser").getDeclaredFields
    var keywords = hbaseSqlFields.filter(x => x.getName.charAt(0).isUpper).map(_.getName)
    keywords ++= sparkSqlFields.filter(x => x.getName.charAt(0).isUpper).map(_.getName)
    keywords.toSeq
  }
}

class HBaseSQLParser extends SqlParser {

  protected val ADD = Keyword("ADD")
  protected val ALTER = Keyword("ALTER")
  protected val COLS = Keyword("COLS")
  protected val DATA = Keyword("DATA")
  protected val DROP = Keyword("DROP")
  protected val EXISTS = Keyword("EXISTS")
  protected val FIELDS = Keyword("FIELDS")
  protected val INPATH = Keyword("INPATH")
  protected val KEY = Keyword("KEY")
  protected val LOAD = Keyword("LOAD")
  protected val LOCAL = Keyword("LOCAL")
  protected val MAPPED = Keyword("MAPPED")
  protected val PRIMARY = Keyword("PRIMARY")
  protected val PARALL = Keyword("PARALL")
  protected val TABLES = Keyword("TABLES")
  protected val VALUES = Keyword("VALUES")
  protected val TERMINATED = Keyword("TERMINATED")
  protected val UPDATE = Keyword("UPDATE")
  protected val DELETE = Keyword("DELETE")
  protected val SET = Keyword("SET")
  protected val EQ = Keyword("=")

  override protected lazy val start: Parser[LogicalPlan] =
    start1 | insert | cte |
      drop | alterDrop | alterAdd |
      insertValues | update | delete | load

  protected lazy val insertValues: Parser[LogicalPlan] =
    INSERT ~> INTO ~> TABLE ~> ident ~ (VALUES ~> "(" ~> values <~ ")") ^^ {
      case tableName ~ valueSeq =>
        val valueStringSeq = valueSeq.map { case v =>
          if (v.value == null) null
          else v.value.toString
        }
        InsertValueIntoTableCommand(tableName, valueStringSeq)
    }

  protected lazy val update: Parser[LogicalPlan] =
    (UPDATE ~> relation <~ SET) ~ rep1sep(updateColumn, ",") ~ (WHERE ~> expression) ^^ {
      case table ~ updateColumns ~ exp =>
        val (columns, values) = updateColumns.unzip
        catalyst.logical.UpdateTable(
          table.asInstanceOf[UnresolvedRelation].tableName,
          columns.map(UnresolvedAttribute.quoted),
          values,
          Filter(exp, table))
    }

  protected lazy val delete: Parser[LogicalPlan] =
    DELETE ~ FROM ~> relation ~ (WHERE ~> expression) ^^ {
      case table ~ exp =>
        catalyst.logical.DeleteFromTable(
          table.asInstanceOf[UnresolvedRelation].tableName,
          Filter(exp, table))
    }

  protected lazy val updateColumn: Parser[(String, String)] =
    ident ~ (EQ ~> literal) ^^ {
      case column ~ value => (column, value.value.toString)
    }

  protected lazy val drop: Parser[LogicalPlan] =
    DROP ~> TABLE ~> ident <~ opt(";") ^^ {
      case tableName => DropHbaseTableCommand(tableName)
    }

  protected lazy val alterDrop: Parser[LogicalPlan] =
    ALTER ~> TABLE ~> ident ~
      (DROP ~> ident) <~ opt(";") ^^ {
      case tableName ~ colName => AlterDropColCommand(tableName, colName)
    }

  protected lazy val alterAdd: Parser[LogicalPlan] =
    ALTER ~> TABLE ~> ident ~
      (ADD ~> tableCol) ~
      (MAPPED ~> BY ~> "(" ~> expressions <~ ")") ^^ {
      case tableName ~ tableColumn ~ mappingInfo =>
        // Since the lexical can not recognize the symbol "=" as we expected, we compose it
        // to expression first and then translate it into Map[String, (String, String)]
        // TODO: Now get the info by hacking, need to change it into normal way if possible
        val infoMap: Map[String, (String, String)] =
          mappingInfo.map { case EqualTo(e1, e2) =>
            val info = e2.toString().substring(1).split('.')
            if (info.length != 2) throw new Exception("\nSyntax Error of Create Table")
            e1.toString().substring(1) ->(info(0), info(1))
          }.toMap
        val familyAndQualifier = infoMap(tableColumn._1)

        AlterAddColCommand(tableName, tableColumn._1, tableColumn._2,
          familyAndQualifier._1, familyAndQualifier._2)
    }

  // Load syntax:
  // LOAD DATA [LOCAL] INPATH filePath INTO TABLE tableName [FIELDS TERMINATED BY char]
  protected lazy val load: Parser[LogicalPlan] =
    (LOAD ~> PARALL.?) ~ (DATA ~> LOCAL.?) ~
      (INPATH ~> stringLit) ~
      (INTO ~> TABLE ~> ident) ~
      (FIELDS ~> TERMINATED ~> BY ~> stringLit).? <~ opt(";") ^^ {
      case isparall ~ isLocal ~ filePath ~ table ~ delimiter =>
        BulkLoadIntoTableCommand(
          filePath, table, isLocal.isDefined,
          delimiter, isparall.isDefined)
    }

  override protected lazy val primitiveType: Parser[DataType] =
    "(?i)string".r ^^^ StringType |
      "(?i)float".r ^^^ FloatType |
      "(?i)(?:int|integer)".r ^^^ IntegerType |
      "(?i)tinyint".r ^^^ ByteType |
      "(?i)(?:short|smallint)".r ^^^ ShortType |
      "(?i)double".r ^^^ DoubleType |
      "(?i)(?:long|bigint)".r ^^^ LongType |
      "(?i)binary".r ^^^ BinaryType |
      "(?i)(?:bool|boolean)".r ^^^ BooleanType |
      fixedDecimalType |
      "(?i)decimal".r ^^^ DecimalType.USER_DEFAULT |
      "(?i)date".r ^^^ DateType |
      "(?i)timestamp".r ^^^ TimestampType |
      varchar |
      "(?i)byte".r ^^^ ByteType

  protected lazy val tableCol: Parser[(String, String)] =
    ident ~ primitiveType ^^ {
      case e1 ~ e2 => (e1, e2.toString.dropRight(4).toUpperCase)
    }

  protected lazy val nameSpace: Parser[String] = ident <~ "."

  protected lazy val tableCols: Parser[Seq[(String, String)]] = repsep(tableCol, ",")

  protected lazy val keys: Parser[Seq[String]] = repsep(ident, ",")

  protected lazy val values: Parser[Seq[Literal]] = repsep(literal, ",")

  protected lazy val expressions: Parser[Seq[Expression]] = repsep(expression, ",")

}
