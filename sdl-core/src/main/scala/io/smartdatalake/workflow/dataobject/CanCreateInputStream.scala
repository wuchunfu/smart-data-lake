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
package io.smartdatalake.workflow.dataobject

import io.smartdatalake.workflow.ActionPipelineContext

import java.io.InputStream

trait CanCreateInputStream {

  def createInputStreams(path: String)(implicit context: ActionPipelineContext): Iterator[InputStream]

  /**
   * Set to true if this DataObject can create multiple InputStreams for one path, e.g. return an Iterator[InputStreams] with multiple entries.
   * In this case SDLB will read all InputStreams from the iterator, and add an additional index-number to the output path.
   */

  def createsMultiInputStreams: Boolean = false

}
