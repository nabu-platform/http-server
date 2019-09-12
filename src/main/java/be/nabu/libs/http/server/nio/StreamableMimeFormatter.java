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
