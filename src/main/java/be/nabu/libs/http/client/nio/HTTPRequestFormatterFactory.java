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

package be.nabu.libs.http.client.nio;

import java.util.Deque;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.libs.nio.api.MessageFormatterFactory;

public class HTTPRequestFormatterFactory implements MessageFormatterFactory<HTTPRequest> {

	private Deque<HTTPRequest> queue;

	public HTTPRequestFormatterFactory(Deque<HTTPRequest> queue) {
		this.queue = queue;
	}
	
	@Override
	public MessageFormatter<HTTPRequest> newMessageFormatter() {
		return new HTTPRequestFormatter(queue);
	}

}
