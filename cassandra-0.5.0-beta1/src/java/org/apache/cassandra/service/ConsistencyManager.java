/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.ReadResponse;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.io.DataInputBuffer;
import java.net.InetAddress;
import org.apache.cassandra.net.IAsyncCallback;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.*;

import org.apache.log4j.Logger;
import org.apache.commons.lang.StringUtils;


class ConsistencyManager implements Runnable
{
	private static Logger logger_ = Logger.getLogger(ConsistencyManager.class);
	
	class DigestResponseHandler implements IAsyncCallback
	{
		List<Message> responses_ = new ArrayList<Message>();

		public void response(Message msg)
		{
			responses_.add(msg);
            if (responses_.size() == ConsistencyManager.this.replicas_.size())
                handleDigestResponses();
        }
		
		private void handleDigestResponses()
		{
            DataInputBuffer bufIn = new DataInputBuffer();
            for (Message response : responses_)
            {
                try
                {
                    byte[] body = response.getMessageBody();
                    bufIn.reset(body, body.length);
                    ReadResponse result = ReadResponse.serializer().deserialize(bufIn);
                    byte[] digest = result.digest();
                    if (!Arrays.equals(row_.digest(), digest))
                    {
                        doReadRepair();
                        break;
                    }
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Error handling responses for " + row_, e);
                }
            }
        }
		
		private void doReadRepair() throws IOException
		{
			IResponseResolver<Row> readResponseResolver = new ReadResponseResolver();
            /* Add the local storage endpoint to the replicas_ list */
            replicas_.add(FBUtilities.getLocalAddress());
			IAsyncCallback responseHandler = new DataRepairHandler(ConsistencyManager.this.replicas_.size(), readResponseResolver);	
            ReadCommand readCommand = constructReadMessage(false);
            Message message = readCommand.makeReadMessage();
            if (logger_.isDebugEnabled())
              logger_.debug("Performing read repair for " + readCommand_.key + " to " + message.getMessageId() + "@[" + StringUtils.join(replicas_, ", ") + "]");
			MessagingService.instance().sendRR(message, replicas_.toArray(new InetAddress[replicas_.size()]), responseHandler);
		}
	}
	
	static class DataRepairHandler implements IAsyncCallback, ICacheExpungeHook<String, String>
	{
		private List<Message> responses_ = new ArrayList<Message>();
		private IResponseResolver<Row> readResponseResolver_;
		private int majority_;
		
		DataRepairHandler(int responseCount, IResponseResolver<Row> readResponseResolver)
		{
			readResponseResolver_ = readResponseResolver;
			majority_ = (responseCount >> 1) + 1;  
		}
		
		public void response(Message message)
		{
			if (logger_.isDebugEnabled())
			  logger_.debug("Received responses in DataRepairHandler : " + message.toString());
			responses_.add(message);
            if (responses_.size() == majority_)
            {
                String messageId = message.getMessageId();
                readRepairTable_.put(messageId, messageId, this);
            }
        }

		public void callMe(String key, String value)
		{
            try
			{
				readResponseResolver_.resolve(new ArrayList<Message>(responses_));
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        }

    }

	private static long scheduledTimeMillis_ = 600;
	private static ICachetable<String, String> readRepairTable_ = new Cachetable<String, String>(scheduledTimeMillis_);
	private final Row row_;
	protected final List<InetAddress> replicas_;
	private final ReadCommand readCommand_;

    public ConsistencyManager(Row row, List<InetAddress> replicas, ReadCommand readCommand)
    {
        row_ = row;
        replicas_ = replicas;
        readCommand_ = readCommand;
    }

	public void run()
	{
        ReadCommand readCommandDigestOnly = constructReadMessage(true);
		try
		{
			Message message = readCommandDigestOnly.makeReadMessage();
            if (logger_.isDebugEnabled())
              logger_.debug("Reading consistency digest for " + readCommand_.key + " from " + message.getMessageId() + "@[" + StringUtils.join(replicas_, ", ") + "]");
            MessagingService.instance().sendRR(message, replicas_.toArray(new InetAddress[replicas_.size()]), new DigestResponseHandler());
		}
		catch (IOException ex)
		{
			throw new RuntimeException(ex);
		}
	}
    
    private ReadCommand constructReadMessage(boolean isDigestQuery)
    {
        ReadCommand readCommand = readCommand_.copy();
        readCommand.setDigestQuery(isDigestQuery);
        return readCommand;
    }
}