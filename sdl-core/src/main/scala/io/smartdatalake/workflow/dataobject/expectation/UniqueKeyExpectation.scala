/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2024 ELCA Informatique SA (<https://www.elca.ch>)
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

package io.smartdatalake.workflow.dataobject.expectation

import com.typesafe.config.Config
import io.smartdatalake.config.SdlConfigObject.DataObjectId
import io.smartdatalake.config.{ConfigurationException, FromConfigFactory, InstanceRegistry}
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.workflow.ActionPipelineContext
import io.smartdatalake.workflow.dataframe.spark.SparkColumn
import io.smartdatalake.workflow.dataframe.{DataFrameFunctions, GenericColumn}
import io.smartdatalake.workflow.dataobject.expectation.ExpectationScope.{ExpectationScope, Job}
import io.smartdatalake.workflow.dataobject.expectation.ExpectationSeverity.ExpectationSeverity
import io.smartdatalake.workflow.dataobject.{DataObject, TableDataObject}


/**
 * Definition of expectation on uniqueness of a given key.
 * Uniqueness is calculated as the fraction of output count distinct on key columns over output count.
 * It supports scope Job and All, but not JobPartition.
 *
 * @param key Optional list of key columns to evaluate uniqueness. If empty primary key definition of DataObject is used if present.
 * @param expectation Optional SQL comparison operator and literal to define expected value for validation. Default is '= 1'.
 *                    Together with the result of the aggExpression evaluation on the left side, it forms the condition to validate the expectation.
 *                    If no expectation is defined, the aggExpression evaluation result is just recorded in metrics.
 * @param precision Number of digits to keep when calculating fraction. Default is 4.
 * @param approximate If approximate count distinct function should be used for counting distinct
 *                    Note that for Spark exact count_distinct is not allows as DataFrame observation and needs a separate query on the DataFrame,
 *                    but approx_count_distinct can be calculated as DataFrame observation.
 *                    On the other hand primary key validation is normally expected to be exact and not approximated.
 * @param approximateRsd Optional Relative Standard Deviation for approximate count distinct.
 *                       Note that not all calculation engines support configuring Rsd with approximate count distinct function.
 */
case class UniqueKeyExpectation(
                                 override val name: String, key: Seq[String] = Seq(),
                                 override val expectation: Option[String] = Some("= 1"),
                                 override val precision: Short = 4,
                                 approximate: Boolean = false,
                                 approximateRsd: Option[Double] = None,
                                 override val scope: ExpectationScope = Job,
                                 override val failedSeverity: ExpectationSeverity = ExpectationSeverity.Error )
  extends Expectation with ExpectationFractionMetricDefaultImpl {
  assert(scope != ExpectationScope.JobPartition, "scope=JobPartition not supported by UniqueKeyExpectation for now")

  override def roundFunc(v: Double): Double = math.floor(v) // use floor to be more aggressive on detecting unique key violations.

  override val description: Option[String] = Some("fraction of output count-distinct over output count")
  private val countDistinctName = "countDistinct" + name.capitalize
  private val countName = if (scope == ExpectationScope.Job) "count" else "countAll"
  def getPrimaryKeyCols(dataObjectId: DataObjectId)(implicit context: ActionPipelineContext): Seq[String] = {
    context.instanceRegistry.get[DataObject](dataObjectId) match {
      case x: TableDataObject =>
        assert(x.table.primaryKey.nonEmpty, s"($dataObjectId) 'table.primaryKey' must be defined on DataObject if parameter 'UniqueKeyExpectation.columns' is empty")
        x.table.primaryKey.getOrElse(throw new IllegalStateException(s"($dataObjectId) 'table.primaryKey' is defined but empty..."))
      case _ => throw ConfigurationException(s"($dataObjectId) If parameter 'columns' is empty, UniqueKeyExpectation must be defined on a DataObject implementing TableDataObject in order to use primary key")
    }
  }
  override def getAggExpressionColumns(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions, context: ActionPipelineContext): Seq[GenericColumn] = {
    val colsToCheck = (if (key.isEmpty) getPrimaryKeyCols(dataObjectId) else key).map(functions.col)
    // if (scope == ExpectationScope.Job && functions.requestSubFeedType() == typeOf[SparkSubFeed])
    val countDistinctCol = if (approximate) functions.approxCountDistinct(functions.struct(colsToCheck:_*), approximateRsd).as(countDistinctName)
    else functions.countDistinct(colsToCheck:_*).as(countDistinctName)
    val countCol = if (scope == ExpectationScope.All) Some(functions.count(functions.col("*")).as(countName)) else None
    Seq(Some(countDistinctCol), countCol).flatten
  }
  def getValidationErrorColumn(dataObjectId: DataObjectId, metrics: Map[String,_], partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): (Seq[SparkColumn],Map[String,_]) = {
    val countDistinct = getMetric[Long](dataObjectId,metrics,countDistinctName)
    val count = getMetric[Long](dataObjectId,metrics,countName)
    val (col, pct) = getValidationErrorColumn(dataObjectId, countDistinct, count)
    val updatedMetrics = metrics.filterKeys(_ != countDistinctName).toMap + (name -> pct)
    (col.map(SparkColumn).toSeq, updatedMetrics)
  }
  override def calculateAsJobDataFrameObservation: Boolean = {
    // only calculate metrics as DataFrame observation for approximate_count_distinct function, as count_distinct is not supported as aggregate function for Spark observations.
    super.calculateAsJobDataFrameObservation && approximate
  }
  override def factory: FromConfigFactory[Expectation] = UniqueKeyExpectation
}

object UniqueKeyExpectation extends FromConfigFactory[Expectation] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): UniqueKeyExpectation = {
    extract[UniqueKeyExpectation](config)
  }
}
