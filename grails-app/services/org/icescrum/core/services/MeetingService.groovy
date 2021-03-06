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
package org.icescrum.core.services


import org.icescrum.core.domain.Meeting
import org.icescrum.core.domain.User
import org.icescrum.core.event.IceScrumEventPublisher
import org.icescrum.core.event.IceScrumEventType

class MeetingService extends IceScrumEventPublisher {

    void save(Meeting meeting, def workspace, User owner) {
        meeting.owner = owner
        workspace.addToMeetings(meeting)
        publishSynchronousEvent(IceScrumEventType.BEFORE_CREATE, meeting)
        meeting.save(flush: true)
        meeting.refresh() // required to initialize collections to empty list
        publishSynchronousEvent(IceScrumEventType.CREATE, meeting)
    }

    void update(Meeting meeting) {
        def dirtyProperties = publishSynchronousEvent(IceScrumEventType.BEFORE_UPDATE, meeting)
        meeting.save(flush: true)
        publishSynchronousEvent(IceScrumEventType.UPDATE, meeting, dirtyProperties)
    }

    void delete(Meeting meeting) {
        def workspace = getWorkspace(meeting)
        def dirtyProperties = publishSynchronousEvent(IceScrumEventType.BEFORE_DELETE, meeting)
        dirtyProperties.workspace = workspace
        meeting.delete()
        workspace.removeFromMeetings(meeting)
        workspace.save(flush: true)
        publishSynchronousEvent(IceScrumEventType.DELETE, meeting, dirtyProperties)
    }

    def getWorkspace(Meeting meeting) {
        return meeting.project ?: meeting.portfolio
    }
}
