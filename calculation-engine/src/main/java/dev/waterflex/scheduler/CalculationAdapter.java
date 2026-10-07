package dev.waterflex.scheduler;
/** Exactly one invocation. Transport failures never trigger another calculation automatically. */
public interface CalculationAdapter {
    CalculationProtocol.Response calculate(CalculationProtocol.Request request);
}
