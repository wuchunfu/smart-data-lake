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

import io.smartdatalake.config.ConfigurationException
import io.smartdatalake.definitions.{ExecutionMode, ExecutionModeWithMainInput}
import io.smartdatalake.util.misc.PerformanceUtils
import io.smartdatalake.workflow.dataobject.{CanCreateDataFrame, CanHandlePartitions, CanWriteDataFrame, DataObject}
import io.smartdatalake.workflow.{ActionPipelineContext, InitSubFeed, SparkSubFeed, SubFeed}
import org.apache.spark.sql.SparkSession

abstract class SparkSubFeedsAction extends SparkAction {

  override def inputs: Seq[DataObject with CanCreateDataFrame]
  override def outputs: Seq[DataObject with CanWriteDataFrame]

  // prepare main input / output
  // this must be lazy because inputs / outputs is evaluated later in subclasses
  val initExecutionModeMainInput: Option[ExecutionModeWithMainInput] = initExecutionMode.collect{ case mode: ExecutionModeWithMainInput => mode }
  lazy val initMainInput: Option[DataObject with CanCreateDataFrame] = initExecutionModeMainInput.flatMap {
    _.mainInputId.map( inputId => inputs.find(_.id.id == inputId).getOrElse(throw ConfigurationException(s"$id has set an initExecutionMode with inputId $inputId, which was not found in inputs")))
  }
  lazy protected val mainInput: Option[DataObject with CanCreateDataFrame] = initMainInput
  .orElse{
    val paritionedInputs = inputs.collect{ case x: CanHandlePartitions => x }.filter(_.partitions.nonEmpty)
    if (paritionedInputs.size==1) paritionedInputs.headOption else None
  }.orElse{
    if (inputs.size==1) inputs.headOption else None
  }
  lazy protected val initMainOutput: Option[DataObject with CanWriteDataFrame] = initExecutionModeMainInput.flatMap {
    _.mainOutputId.map( outputId => outputs.find(_.id.id == outputId).getOrElse(throw ConfigurationException(s"$id has set an initExecutionMode with outputId $outputId, which was not found in outputs")))
  }
  lazy protected val mainOutput: Option[DataObject with CanWriteDataFrame] = initMainOutput
  .orElse{
    val paritionedOutputs = outputs.collect{ case x: CanHandlePartitions => x }.filter(_.partitions.nonEmpty)
    if (paritionedOutputs.size==1) paritionedOutputs.headOption else None
  }.orElse{
    if (outputs.size==1) outputs.headOption else None
  }

  /**
   * Transform [[SparkSubFeed]]'s.
   * To be implemented by subclasses.
   *
   * @param subFeeds [[SparkSubFeed]]'s to be transformed
   * @return transformed [[SparkSubFeed]]'s
   */
  def transform(subFeeds: Seq[SparkSubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SparkSubFeed]

  private def doTransform(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SparkSubFeed] = {
    // convert subfeeds to SparkSubFeed type or initialize if not yet existing
    var preparedSubFeeds = subFeeds.map( SparkSubFeed.fromSubFeed )
    // apply init execution mode if there are no partition values given in command line
    require(initExecutionMode.isEmpty || mainInput.isDefined, throw ConfigurationException(s"$id has set an initExecutionMode without inputId but there are ${inputs.size} inputs with partitions. Please specify initExecutionMode.inputId to select input."))
    require(initExecutionMode.isEmpty || mainOutput.isDefined, throw ConfigurationException(s"$id has set an initExecutionMode without outputId but there are ${outputs.size} outputs with partitions. Please specify initExecutionMode.outputId to select output."))
    val mainInputSubFeed = mainInput.flatMap( input => subFeeds.find(_.dataObjectId==input.id))
    preparedSubFeeds = if ( initExecutionMode.isDefined && mainInputSubFeed.exists(_.isInstanceOf[InitSubFeed]) && mainInputSubFeed.exists(_.partitionValues.isEmpty)) {
      preparedSubFeeds.map {
        subFeed =>
          if (subFeed.dataObjectId==mainInput.get.id) subFeed.copy(partitionValues = ActionHelper.applyExecutionMode(initExecutionMode.get, id, mainInput.get, mainOutput.get, subFeed.partitionValues))
          else subFeed
      }
    } else preparedSubFeeds
    // break lineage if requested
    preparedSubFeeds = if (breakDataFrameLineage) preparedSubFeeds.map(_.breakLineage) else preparedSubFeeds
    // persist if requested
    preparedSubFeeds = if (persist) preparedSubFeeds.map(_.persist) else preparedSubFeeds
    // transform
    val transformedSubFeeds = transform(preparedSubFeeds)
    // update partition values to output's partition columns and update dataObjectId
    transformedSubFeeds.map {
      subFeed =>
        val output = outputs.find(_.id == subFeed.dataObjectId)
          .getOrElse(throw ConfigurationException(s"No output found for result ${subFeed.dataObjectId} in $id. Configured outputs are ${outputs.map(_.id.id).mkString(", ")}."))
        validateAndUpdateSubFeedPartitionValues(output, subFeed)
    }
  }

  /**
   * Generic init implementation for Action.init
   * */
  override final def init(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == inputs.size, s"Number of subFeed's must match number of inputs for SparkSubFeedActions (Action $id, subfeed's ${subFeeds.map(_.dataObjectId).mkString(",")}, inputs ${inputs.map(_.id).mkString(",")})")
    doTransform(subFeeds)
  }

  /**
   * Action.exec implementation
   */
  override final def exec(subFeeds: Seq[SubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SubFeed] = {
    assert(subFeeds.size == inputs.size, s"Number of subFeed's must match number of inputs for SparkSubFeedActions (Action $id, subfeed's ${subFeeds.map(_.dataObjectId).mkString(",")}, inputs ${inputs.map(_.id).mkString(",")})")
    val mainInputSubFeed = subFeeds.find(_.dataObjectId == mainInput.get.id).getOrElse(throw new IllegalStateException(s"subFeed for main input ${mainInput.get.id} not found"))
    //transform
    val transformedSubFeeds = doTransform(subFeeds)
    // write output
    outputs.foreach { output =>
      val subFeed = transformedSubFeeds.find(_.dataObjectId == output.id).getOrElse(throw new IllegalStateException(s"subFeed for output ${output.id} not found"))
      val msg = s"writing DataFrame to ${output.id}" + (if (subFeed.partitionValues.nonEmpty) s", partitionValues ${subFeed.partitionValues.mkString(" ")}" else "")
      logger.info(s"($id) start " + msg)
      setSparkJobMetadata(Some(msg))
      val (_,d) = PerformanceUtils.measureDuration {
        writeSubFeed(runtimeExecutionMode(mainInputSubFeed.isDAGStart), subFeed, output)
      }
      setSparkJobMetadata()
      val finalMetricsInfos = getFinalMetrics(output.id).map(_.getMainInfos)
      logger.info(s"($id) finished writing DataFrame to ${output.id}: duration=$d" + finalMetricsInfos.map(" "+_.map( x => x._1+"="+x._2).mkString(" ")).getOrElse(""))
    }
    // return
    transformedSubFeeds
  }

  /**
   * Enriches SparkSubFeeds with DataFrame if not existing
   *
   * @param inputs input data objects.
   * @param subFeeds input SubFeeds.
   */
  protected def enrichSubFeedsDataFrame(inputs: Seq[DataObject with CanCreateDataFrame], subFeeds: Seq[SparkSubFeed])(implicit session: SparkSession, context: ActionPipelineContext): Seq[SparkSubFeed] = {
    assert(inputs.size==subFeeds.size, s"Number of inputs must match number of subFeeds given for $id")
    inputs.map { input =>
      val subFeed = subFeeds.find(_.dataObjectId == input.id).getOrElse(throw new IllegalStateException(s"subFeed for input ${input.id} not found"))
      enrichSubFeedDataFrame(input, subFeed, runtimeExecutionMode(subFeed.isDAGStart), context.phase)
    }
  }
}
