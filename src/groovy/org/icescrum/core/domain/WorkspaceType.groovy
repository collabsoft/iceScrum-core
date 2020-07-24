/*
 * Copyright (c) 2020 Kagilum.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Nicolas Noullet (nnoullet@kagilum.com)
 *
 */
package org.icescrum.core.domain

class WorkspaceType {
    static final String PROJECT = 'project'
    static final String PORTFOLIO = 'portfolio'
    static final Map prefix = ["${WorkspaceType.PROJECT}": 'p', "${WorkspaceType.PORTFOLIO}": 'f']
    static final Map keyProperty = ["${WorkspaceType.PROJECT}": 'pkey', "${WorkspaceType.PORTFOLIO}": 'fkey']

    static String getPrefixUrl(workspaceType) {
        return this.prefix.get("$workspaceType")
    }

    static String getKeyProperty(workspaceType) {
        return this.keyProperty.get("$workspaceType")
    }
}
