/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2023 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.smartdatalake.workflow.action.generic.transformer

import io.smartdatalake.config.InstanceRegistry
import io.smartdatalake.testutils.{MockDataObject, TestUtil}
import io.smartdatalake.workflow.action.CustomDataFrameAction
import io.smartdatalake.workflow.dataframe.spark.SparkSubFeed
import io.smartdatalake.workflow.{ActionPipelineContext, ExecutionPhase}
import org.apache.spark.sql.SparkSession
import org.scalatest.FunSuite

class DebugTransformerTest extends FunSuite {
  protected implicit val session: SparkSession = TestUtil.session

  import session.implicits._

  implicit val instanceRegistry: InstanceRegistry = new InstanceRegistry
  implicit val contextInit: ActionPipelineContext = TestUtil.getDefaultActionPipelineContext
  val contextExec: ActionPipelineContext = contextInit.copy(phase = ExecutionPhase.Exec)

  test("copy load with transformer, a regular and a skipped input, skipped input is reset after decision to execute Action was made") {

    // setup DataObjects
    val srcDO1 = MockDataObject("src1").register
    val srcDO2 = MockDataObject("src2").register
    val tgtDO1 = MockDataObject("tgt1", partitions = Seq("lastname"), primaryKey = Some(Seq("lastname", "firstname"))).register

    // prepare
    val customTransformerConfig = SQLDfsTransformer(code = Map(tgtDO1.id.id -> "select * from src1 union all select * from src2"))
    val debugDfTransformer = DebugTransformer(printSchema = true, show=true, showOptions = Map("vertical" -> "true"), explain=true, explainOptions=Map("mode" -> "extended"))
    val debugDfsTransformer = DfTransformerWrapperDfsTransformer(transformer = debugDfTransformer, subFeedsToApply = Seq("src1"))
    val l1 = Seq(("jonson", "rob", 5)).toDF("lastname", "firstname", "rating")
    srcDO1.writeSparkDataFrame(l1, Seq())
    val l2 = Seq(("doe", "bob", 3)).toDF("lastname", "firstname", "rating")
    srcDO2.writeSparkDataFrame(l2, Seq())

    // execute - we can just check that there are no exceptions, but looking for the logs is difficult
    val action1 = CustomDataFrameAction("ca", List(srcDO1.id, srcDO2.id), List(tgtDO1.id)
      , transformers = Seq(customTransformerConfig,debugDfsTransformer))
    instanceRegistry.register(action1)
    val srcSubFeed1 = SparkSubFeed(None, "src1", Seq())
    val srcSubFeed2 = SparkSubFeed(None, "src2", Seq())
    action1.preInit(Seq(srcSubFeed1, srcSubFeed2), Seq())
    action1.preExec(Seq(srcSubFeed1, srcSubFeed2))(contextExec)
    action1.exec(Seq(srcSubFeed1, srcSubFeed2))(contextExec)
  }
}
