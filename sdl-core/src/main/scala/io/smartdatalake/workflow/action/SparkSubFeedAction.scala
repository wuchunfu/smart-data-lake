/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
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
package io.smartdatalake.workflow.action

import io.smartdatalake.util.misc.PerformanceUtils
import io.smartdatalake.workflow.dataobject.{CanCreateDataFrame, CanWriteDataFrame, DataObject}
import io.smartdatalake.workflow.{ActionPipelineContext, SparkSubFeed, SubFeed}
import org.apache.spark.sql.SparkSession

import scala.util.Try

abstract class SparkSubFeedAction extends SparkAction {

  /**
   * Input [[DataObject]] which can CanCreateDataFrame
   */
  def input: DataObject with CanCreateDataFrame

  /**
   * Output [[DataObject]] which can CanWriteDataFrame
   */
  def output:  DataObject with CanWriteDataFrame

  /**
   * Recursive Inputs are not supported on SparkSubFeedAction (only on SparkSubFeedsAction) so set to empty Seq
   *  @return
   */
  override def recursiveInputs: Seq[DataObject with CanCreateDataFrame] = Seq()

  /**
   * Transform a [[SparkSubFeed]].
   * To be implemented by subclasses.
   *
   * @param subFeed [[SparkSubFeed]] to be transformed
   * @return transformed [[SparkSubFeed]]
   */
  def transform(subFeed: SparkSubFeed)(implicit session: SparkSession, context: ActionPipelineContext): SparkSubFeed

  private def doTransform(subFeed: SparkSubFeed)(implicit session: SparkSession, context: ActionPipelineContext): SparkSubFeed = {
    // apply execution mode
    var preparedSubFeed = executionModeResult.get match {
      case Some((newPartitionValues, newFilter)) => subFeed.copy(partitionValues = newPartitionValues, filter = newFilter)
      case None => subFeed
    }
    // prepare as input SubFeed
    preparedSubFeed = prepareInputSubFeed(preparedSubFeed, input)
    // enrich with fresh DataFrame if needed
    preparedSubFeed = enrichSubFeedDataFrame(input, preparedSubFeed, context.phase)
    // transform and update dataObjectId
    val transformedSubFeed = transform(preparedSubFeed).copy(dataObjectId = output.id)
    // update partition values to output's partition columns
    validateAndUpdateSubFeedPartitionValues(output, transformedSubFeed)
  }

  /**
   * Action.init implementation
   */
  override final def init(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == 1, s"Only one subfeed allowed for SparkSubFeedActions (Action $id, inputSubfeed's ${subFeeds.map(_.dataObjectId).mkString(",")})")
    // convert subfeed to SparkSubFeed type or initialize if not yet existing
    val subFeed = SparkSubFeed.fromSubFeed(subFeeds.head)
    // evaluate execution mode and store result
    executionModeResult = Try(
      executionMode.flatMap(_.apply(id, input, output, subFeed))
    ).recover {
      case ex: NoDataToProcessDontStopWarning =>
        // return empty output subfeed if "no data dont stop"
        val outputSubFeed = SparkSubFeed(dataFrame = None, dataObjectId = output.id, partitionValues = Seq())
        // update partition values to output's partition columns and update dataObjectId
        validateAndUpdateSubFeedPartitionValues(output, outputSubFeed)
        // rethrow exception with fake results added. The DAG will pass the fake results to further actions.
        throw ex.copy(results = Some(Seq(outputSubFeed)))
    }
    // transform
    val transformedSubFeed = doTransform(subFeed)
    // check output
    output.init(transformedSubFeed.dataFrame.get, transformedSubFeed.partitionValues)
    // return
    Seq(updateSubFeedAfterWrite(transformedSubFeed))
  }

  /**
   * Action.exec implementation
   */
  override final def exec(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == 1, s"Only one subfeed allowed for SparkSubFeedActions (Action $id, inputSubfeed's ${subFeeds.map(_.dataObjectId).mkString(",")})")
    // convert subfeed to SparkSubFeed type or initialize if not yet existing
    val subFeed = SparkSubFeed.fromSubFeed(subFeeds.head)
    // transform
    val transformedSubFeed = doTransform(subFeed)
    // write output
    val msg = s"writing to ${output.id}" + (if (transformedSubFeed.partitionValues.nonEmpty) s", partitionValues ${transformedSubFeed.partitionValues.mkString(" ")}" else "")
    logger.info(s"($id) start " + msg)
    setSparkJobMetadata(Some(msg))
    val (noData,d) = PerformanceUtils.measureDuration {
      writeSubFeed(transformedSubFeed, output)
    }
    setSparkJobMetadata()
    val metricsLog = if (noData) ", no data found"
    else getFinalMetrics(output.id).map(_.getMainInfos).map(" "+_.map( x => x._1+"="+x._2).mkString(" ")).getOrElse("")
    logger.info(s"($id) finished writing DataFrame to ${output.id}: jobDuration=$d" + metricsLog)
    // return
    Seq(updateSubFeedAfterWrite(transformedSubFeed))
  }

  override final def postExec(inputSubFeeds: Seq[SubFeed], outputSubFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.postExec(inputSubFeeds, outputSubFeeds)
    assert(inputSubFeeds.size == 1, s"Only one inputSubFeed allowed for SparkSubFeedActions (Action $id, inputSubfeed's ${inputSubFeeds.map(_.dataObjectId).mkString(",")})")
    assert(outputSubFeeds.size == 1, s"Only one outputSubFeed allowed for SparkSubFeedActions (Action $id, inputSubfeed's ${outputSubFeeds.map(_.dataObjectId).mkString(",")})")
    postExecSubFeed(inputSubFeeds.head, outputSubFeeds.head)
  }

  def postExecSubFeed(inputSubFeed: SubFeed, outputSubFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Unit = Unit /* NOP */

}
