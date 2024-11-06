/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.http.server.nio;

import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.EventfulReadableContainer;
import be.nabu.utils.io.api.EventfulSubscriber;
import be.nabu.utils.io.api.EventfulSubscription;
import be.nabu.utils.mime.impl.PullableMimeFormatter;

public class StreamableMimeFormatter extends PullableMimeFormatter implements EventfulReadableContainer<ByteBuffer> {

	@Override
	public EventfulSubscription availableData(EventfulSubscriber subscriber) {
		if (currentEventful != null) {
			return currentEventful.availableData(subscriber);
		}
		return null;
	}
	
}
