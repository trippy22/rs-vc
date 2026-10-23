package com.rsvc;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReconnectPolicyTest
{
    @Test public void retriesAreBoundedAndBackOff()
    {
        ReconnectPolicy policy = new ReconnectPolicy();
        for (long delay : new long[] { 1000, 2000, 4000, 8000, 16000, 30000, -1, -1 }) assertEquals(delay, policy.nextDelayMillis());
        policy.reset(); assertEquals(1000, policy.nextDelayMillis());
    }
}
