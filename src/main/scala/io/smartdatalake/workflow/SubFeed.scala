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
package io.smartdatalake.workflow

import io.smartdatalake.config.SdlConfigObject.DataObjectId
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.workflow.dataobject.FileRef
import org.apache.spark.sql.DataFrame

/**
 * A SubFeed transports references to data between Actions.
 * Data can be represented by different technologies like Files or DataFrame.
 */
trait SubFeed extends DAGResult {
  def dataObjectId: DataObjectId
  def partitionValues: Seq[PartitionValues]

  /**
   * Break lineage.
   * This means to discard an existing DataFrame or List of FileRefs, so that it is requested again from the DataObject.
   * This is usable to break long DataFrame Lineages over multiple Actions and instead reread the data from an intermediate table
   * @return
   */
  def breakLineage(): SubFeed

  def clearPartitionValues(): SubFeed

  def updatePartitionValues(partitions: Seq[String]): SubFeed

  override def resultId: String = dataObjectId.id
}

/**
 * A SparkSubFeed is used to transport [[DataFrame]]'s between Actions.
 *
 * @param dataFrame Spark [[DataFrame]] to be processed
 * @param dataObjectId id of the DataObject this SubFeed corresponds to
 * @param partitionValues Values of Partitions transported by this SubFeed
 */
case class SparkSubFeed(dataFrame: Option[DataFrame], override val dataObjectId: DataObjectId, override val partitionValues: Seq[PartitionValues])
  extends SubFeed {
  override def breakLineage(): SparkSubFeed = {
    this.copy(dataFrame = None)
  }
  override def clearPartitionValues(): SparkSubFeed = {
    this.copy(partitionValues = Seq())
  }
  override def updatePartitionValues(partitions: Seq[String]): SparkSubFeed = {
    val updatedPartitionValues = partitionValues.map( pvs => PartitionValues(pvs.elements.filterKeys(partitions.contains))).filter(_.nonEmpty)
    this.copy(partitionValues = updatedPartitionValues)
  }
  def persist: SparkSubFeed = {
    copy(dataFrame = this.dataFrame.map(_.persist))
  }
}
object SparkSubFeed {
  def fromSubFeed( subFeed: SubFeed ): SparkSubFeed = {
    subFeed match {
      case sparkSubFeed: SparkSubFeed => sparkSubFeed
      case _ => SparkSubFeed(None, subFeed.dataObjectId, subFeed.partitionValues)
    }
  }
}

/**
 * A FileSubFeed is used to transport references to files between Actions.
 *
 * @param fileRefs path to files to be processed
 * @param dataObjectId id of the DataObject this SubFeed corresponds to
 * @param partitionValues Values of Partitions transported by this SubFeed
 * @param processedInputFileRefs used to remember processed input FileRef's for post processing (e.g. delete after read)
 */
case class FileSubFeed(fileRefs: Option[Seq[FileRef]],
                       override val dataObjectId: DataObjectId,
                       override val partitionValues: Seq[PartitionValues],
                       processedInputFileRefs: Option[Seq[FileRef]] = None
                      )
  extends SubFeed {
  override def breakLineage(): FileSubFeed = {
    this.copy(fileRefs = None)
  }
  override def clearPartitionValues(): FileSubFeed = {
    this.copy(partitionValues = Seq())
  }
  override def updatePartitionValues(partitions: Seq[String]): FileSubFeed = {
    val updatedPartitionValues = partitionValues.map( pvs => PartitionValues(pvs.elements.filterKeys(partitions.contains))).filter(_.nonEmpty)
    this.copy(partitionValues = updatedPartitionValues)
  }
}
object FileSubFeed {
  def fromSubFeed( subFeed: SubFeed ): FileSubFeed = {
    subFeed match {
      case fileSubFeed: FileSubFeed => fileSubFeed
      case _ => FileSubFeed(None, subFeed.dataObjectId, subFeed.partitionValues)
    }
  }
}

/**
 * A InitSubFeed is used to initialize first Nodes of an [[DAG]].
 *
 * @param dataObjectId id of the DataObject this SubFeed corresponds to
 * @param partitionValues Values of Partitions transported by this SubFeed
 */
case class InitSubFeed(override val dataObjectId: DataObjectId, override val partitionValues: Seq[PartitionValues])
  extends SubFeed {
  override def breakLineage(): InitSubFeed = this
  override def clearPartitionValues(): InitSubFeed = {
    this.copy(partitionValues = Seq())
  }
  override def updatePartitionValues(partitions: Seq[String]): InitSubFeed = {
    val updatedPartitionValues = partitionValues.map( pvs => PartitionValues(pvs.elements.filterKeys(partitions.contains))).filter(_.nonEmpty)
    this.copy(partitionValues = updatedPartitionValues)
  }
}