/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.virtualvehicle;

import com.google.common.util.concurrent.Uninterruptibles;
import com.google.inject.assistedinject.Assisted;
import org.opentcs.common.LoopbackAdapterConstants;
import org.opentcs.customizations.kernel.KernelExecutor;
import org.opentcs.data.ObjectPropConstants;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.model.Vehicle.Orientation;
import org.opentcs.data.order.Route.Step;
import org.opentcs.drivers.vehicle.*;
import org.opentcs.drivers.vehicle.management.VehicleProcessModelTO;
import org.opentcs.drivers.vehicle.messages.SetSpeedMultiplier;
import org.opentcs.util.CyclicTask;
import org.opentcs.util.ExplainedBoolean;
import org.opentcs.virtualvehicle.VelocityController.WayEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.beans.PropertyChangeEvent;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * A {@link VehicleCommAdapter} that does not really communicate with a physical vehicle but roughly
 * simulates one.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class LoopbackCommunicationAdapter
        extends BasicVehicleCommAdapter
        implements SimVehicleCommAdapter {

    /**
     * The name of the load handling device set by this adapter.
     */
    public static final String LHD_NAME = "default";
    /**
     * This class's Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LoopbackCommunicationAdapter.class);
    /**
     * An error code indicating that there's a conflict between a load operation and the vehicle's
     * current load state.
     */
    private static final String LOAD_OPERATION_CONFLICT = "cannotLoadWhenLoaded";
    /**
     * An error code indicating that there's a conflict between an unload operation and the vehicle's
     * current load state.
     */
    private static final String UNLOAD_OPERATION_CONFLICT = "cannotUnloadWhenNotLoaded";
    /**
     * The time by which to advance the velocity controller per step (in ms).
     */
    private static final int ADVANCE_TIME = 100;
    /**
     * This instance's configuration.
     */
    private final VirtualVehicleConfiguration configuration;
    /**
     * The adapter components factory.
     */
    private final LoopbackAdapterComponentsFactory componentsFactory;
    /**
     * The kernel's executor.
     */
    private final ExecutorService kernelExecutor;
    /**
     * The task simulating the virtual vehicle's behaviour.
     */
    private CyclicTask vehicleSimulationTask;
    /**
     * The boolean flag to check if execution of the next command is allowed.
     */
    private boolean singleStepExecutionAllowed;
    /**
     * The vehicle to this comm adapter instance.
     */
    private final Vehicle vehicle;
    /**
     * The vehicle's load state.
     */
    private LoadState loadState = LoadState.EMPTY;
    /**
     * Whether the loopback adapter is initialized or not.
     */
    private boolean initialized;

    /**
     * Creates a new instance.
     *
     * @param componentsFactory The factory providing additional components for this adapter.
     * @param configuration     This class's configuration.
     * @param vehicle           The vehicle this adapter is associated with.
     * @param kernelExecutor    The kernel's executor.
     */
    @Inject
    public LoopbackCommunicationAdapter(LoopbackAdapterComponentsFactory componentsFactory,
                                        VirtualVehicleConfiguration configuration,
                                        @Assisted Vehicle vehicle,
                                        @KernelExecutor ExecutorService kernelExecutor) {
        super(new LoopbackVehicleModel(vehicle),
                configuration.commandQueueCapacity(),
                1,
                configuration.rechargeOperation());
        this.vehicle = requireNonNull(vehicle, "vehicle");
        this.configuration = requireNonNull(configuration, "configuration");
        this.componentsFactory = requireNonNull(componentsFactory, "componentsFactory");
        this.kernelExecutor = requireNonNull(kernelExecutor, "kernelExecutor");
    }

    @Override
    public void initialize() {
        if (isInitialized()) {
            return;
        }
        super.initialize();

        String initialPos
                = vehicle.getProperties().get(LoopbackAdapterConstants.PROPKEY_INITIAL_POSITION);
        if (initialPos == null) {
            @SuppressWarnings("deprecation")
            String deprecatedInitialPos
                    = vehicle.getProperties().get(ObjectPropConstants.VEHICLE_INITIAL_POSITION);
            initialPos = deprecatedInitialPos;
        }
        if (initialPos != null) {
            initVehiclePosition(initialPos);
        }
        getProcessModel().setVehicleState(Vehicle.State.IDLE);
        initialized = true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) {
            return;
        }
        super.terminate();
        initialized = false;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        super.propertyChange(evt);

        if (!((evt.getSource()) instanceof LoopbackVehicleModel)) {
            return;
        }
        if (Objects.equals(evt.getPropertyName(),
                VehicleProcessModel.Attribute.LOAD_HANDLING_DEVICES.name())) {
            if (!getProcessModel().getVehicleLoadHandlingDevices().isEmpty()
                    && getProcessModel().getVehicleLoadHandlingDevices().get(0).isFull()) {
                loadState = LoadState.FULL;
            } else {
                loadState = LoadState.EMPTY;
            }
        }
    }

    @Override
    public synchronized void enable() {
        if (isEnabled()) {
            return;
        }
        getProcessModel().getVelocityController().addVelocityListener(getProcessModel());
        // Create task for vehicle simulation.
        vehicleSimulationTask = new VehicleSimulationTask();
        Thread simThread = new Thread(vehicleSimulationTask, getName() + "-simulationTask");
        LOG.debug("【LoopbackCommunicationAdapter】[{}任务线程] 将被激活", simThread.getName());
        simThread.start();
        super.enable();
    }

    @Override
    public synchronized void disable() {
        if (!isEnabled()) {
            return;
        }
        // Disable vehicle simulation.
        vehicleSimulationTask.terminate();
        vehicleSimulationTask = null;
        getProcessModel().getVelocityController().removeVelocityListener(getProcessModel());
        super.disable();
    }

    @Override
    public LoopbackVehicleModel getProcessModel() {
        return (LoopbackVehicleModel) super.getProcessModel();
    }

    @Override
    @Deprecated
    protected List<org.opentcs.drivers.vehicle.VehicleCommAdapterPanel> createAdapterPanels() {
        return Arrays.asList(componentsFactory.createPanel(this));
    }

    @Override
    public synchronized void sendCommand(MovementCommand cmd) {
        requireNonNull(cmd, "cmd");

        // Reset the execution flag for single-step mode.
        singleStepExecutionAllowed = false;
        // Don't do anything else - the command will be put into the sentQueue
        // automatically, where it will be picked up by the simulation task.
    }

    @Override
    public void processMessage(Object message) {
        // Process LimitSpeeed message which might pause the vehicle.
        if (message instanceof SetSpeedMultiplier) {
            SetSpeedMultiplier lsMessage = (SetSpeedMultiplier) message;
            int multiplier = lsMessage.getMultiplier();
            getProcessModel().setVehiclePaused(multiplier == 0);
        }
    }

    @Override
    public synchronized void initVehiclePosition(String newPos) {
        kernelExecutor.submit(() -> {
            LOG.debug("驱动{}将执行小车{}位置初始化{}", this.getName(), this.vehicle.getName(), newPos);
            getProcessModel().setVehiclePosition(newPos);
        });
    }

    @Override
    public synchronized ExplainedBoolean canProcess(List<String> operations) {
        requireNonNull(operations, "operations");

        LOG.debug("{}: Checking processability of {}...", getName(), operations);
        boolean canProcess = true;
        String reason = "";

        // Do NOT require the vehicle to be IDLE or CHARGING here!
        // That would mean a vehicle moving to a parking position or recharging location would always
        // have to finish that order first, which would render a transport order's dispensable flag
        // useless.
        boolean loaded = loadState == LoadState.FULL;
        Iterator<String> opIter = operations.iterator();
        while (canProcess && opIter.hasNext()) {
            final String nextOp = opIter.next();
            // If we're loaded, we cannot load another piece, but could unload.
            if (loaded) {
                if (nextOp.startsWith(getProcessModel().getLoadOperation())) {
                    canProcess = false;
                    reason = LOAD_OPERATION_CONFLICT;
                } else if (nextOp.startsWith(getProcessModel().getUnloadOperation())) {
                    loaded = false;
                }
            } // If we're not loaded, we could load, but not unload.
            else if (nextOp.startsWith(getProcessModel().getLoadOperation())) {
                loaded = true;
            } else if (nextOp.startsWith(getProcessModel().getUnloadOperation())) {
                canProcess = false;
                reason = UNLOAD_OPERATION_CONFLICT;
            }
        }
        if (!canProcess) {
            LOG.debug("{}: Cannot process {}, reason: '{}'", getName(), operations, reason);
        }
        return new ExplainedBoolean(canProcess, reason);
    }

    @Override
    protected synchronized boolean canSendNextCommand() {
        return super.canSendNextCommand()
                && (!getProcessModel().isSingleStepModeEnabled() || singleStepExecutionAllowed);
    }

    @Override
    protected synchronized void connectVehicle() {
    }

    @Override
    protected synchronized void disconnectVehicle() {
    }

    @Override
    protected synchronized boolean isVehicleConnected() {
        return true;
    }

    @Override
    protected VehicleProcessModelTO createCustomTransferableProcessModel() {
        return new LoopbackVehicleModelTO()
                .setLoadOperation(getProcessModel().getLoadOperation())
                .setMaxAcceleration(getProcessModel().getMaxAcceleration())
                .setMaxDeceleration(getProcessModel().getMaxDecceleration())
                .setMaxFwdVelocity(getProcessModel().getMaxFwdVelocity())
                .setMaxRevVelocity(getProcessModel().getMaxRevVelocity())
                .setOperatingTime(getProcessModel().getOperatingTime())
                .setSingleStepModeEnabled(getProcessModel().isSingleStepModeEnabled())
                .setUnloadOperation(getProcessModel().getUnloadOperation())
                .setVehiclePaused(getProcessModel().isVehiclePaused());
    }

    /**
     * Triggers a step in single step mode.
     */
    public synchronized void trigger() {
        singleStepExecutionAllowed = true;
    }

    /**
     * A task simulating a vehicle's behaviour.
     */
    private class VehicleSimulationTask
            extends CyclicTask {

        /**
         * The time that has passed for the velocity controller whenever
         * <em>advanceTime</em> has passed for real.
         */
        private int simAdvanceTime;

        /**
         * Creates a new VehicleSimluationTask.
         */
        private VehicleSimulationTask() {
            super(0);
        }

        @Override
        protected void runActualTask() {
            //基础驱动任务在于判断是否可以下发指令给驱动并执行下发: sendCommand(curCmd)->getSentQueue().add(curCmd)->getProcessModel().commandSent(curCmd)
            //自定义驱动任务在于处理【已下发、未执行的SentQueue】，执行SentQueue，回调告知执行情况
            final MovementCommand curCommand;
            synchronized (LoopbackCommunicationAdapter.this) {
                curCommand = getSentQueue().peek();
            }
            simAdvanceTime = (int) (ADVANCE_TIME * configuration.simulationTimeFactor());
            if (curCommand == null) {
                Uninterruptibles.sleepUninterruptibly(ADVANCE_TIME, TimeUnit.MILLISECONDS);
                getProcessModel().getVelocityController().advanceTime(simAdvanceTime);
            } else {
                LOG.debug("当前所有sentQueue{}", getSentQueue());
                LOG.debug("peek获取【已发送未执行队列sentQueue】中的的当前运行指令MovementCommand:{}", curCommand);
                // If we were told to move somewhere, simulate the journey.
                LOG.debug("Processing MovementCommand...");
                final Step curStep = curCommand.getStep();
                // Simulate the movement.
                LOG.debug("=========适配器开始执行移动指令MovementCommand========", curStep);
                simulateMovement(curStep);
                LOG.debug("适配器已驱动小车执行移动指令MovementCommand.Step{}", curStep);
                LOG.debug("适配器检查{}需要执行MovementCommand.Operation{}", !curCommand.isWithoutOperation(), curCommand.getOperation());
                // Simulate processing of an operation.
                if (!curCommand.isWithoutOperation()) {
                    simulateOperation(curCommand.getOperation());
                }
                LOG.debug("Processed MovementCommand.");
                LOG.debug("=========适配器执行完成移动指令MovementCommand========", curStep);
                if (!isTerminated()) {

                    LOG.debug("=========适配器开始更新小车状态：========", curStep);
                    // Set the vehicle's state back to IDLE, but only if there aren't
                    // any more movements to be processed.
                    LOG.debug("如果【待执行SentQueue】 <= 1 && 【待发送CommandQueue】为空，置Vehicle.State.IDLE");
                    if (getSentQueue().size() <= 1 && getCommandQueue().isEmpty()) {
                        getProcessModel().setVehicleState(Vehicle.State.IDLE);
                    }
                    // Update GUI.
                    synchronized (LoopbackCommunicationAdapter.this) {
                        MovementCommand sentCmd = getSentQueue().poll();
                        LOG.debug("getSentQueue().poll()移除已经被执行掉的sentCmd", sentCmd);
                        // If the command queue was cleared in the meantime, the kernel
                        // might be surprised to hear we executed a command we shouldn't
                        // have, so we only peek() at the beginning of this method and
                        // poll() here. If sentCmd is null, the queue was probably cleared
                        // and we shouldn't report anything back.
                        if (sentCmd != null && sentCmd.equals(curCommand)) {
                            LOG.debug("告知小车模型指令{}已被执行", curCommand);
                            // Let the vehicle manager know we've finished this command.
                            getProcessModel().commandExecuted(curCommand);
                            LoopbackCommunicationAdapter.this.notify();
                        }
                    }
                    LOG.debug("=========适配器完成更新小车状态========");

                }
            }
        }

        /**
         * Simulates the vehicle's movement. If the method parameter is null,
         * then the vehicle's state is failure and some false movement
         * must be simulated. In the other case normal step
         * movement will be simulated.
         *
         * @param step A step
         * @throws InterruptedException If an exception occured while sumulating
         */
        private void simulateMovement(Step step) {
            if (step.getPath() == null) {
                return;
            }

            Orientation orientation = step.getVehicleOrientation();
            long pathLength = step.getPath().getLength();
            int maxVelocity;
            switch (orientation) {
                case BACKWARD:
                    maxVelocity = step.getPath().getMaxReverseVelocity();
                    break;
                default:
                    maxVelocity = step.getPath().getMaxVelocity();
                    break;
            }
            String pointName = step.getDestinationPoint().getName();

            getProcessModel().setVehicleState(Vehicle.State.EXECUTING);
            getProcessModel().getVelocityController().addWayEntry(new WayEntry(pathLength,
                    maxVelocity,
                    pointName,
                    orientation));
            // Advance the velocity controller by small steps until the
            // controller has processed all way entries.
            while (getProcessModel().getVelocityController().hasWayEntries() && !isTerminated()) {
                WayEntry wayEntry = getProcessModel().getVelocityController().getCurrentWayEntry();
                Uninterruptibles.sleepUninterruptibly(ADVANCE_TIME, TimeUnit.MILLISECONDS);
                getProcessModel().getVelocityController().advanceTime(simAdvanceTime);
                WayEntry nextWayEntry = getProcessModel().getVelocityController().getCurrentWayEntry();
                if (wayEntry != nextWayEntry) {
                    // Let the vehicle manager know that the vehicle has reached
                    // the way entry's destination point.
                    getProcessModel().setVehiclePosition(wayEntry.getDestPointName());
                }
            }
        }

        /**
         * Simulates an operation.
         *
         * @param operation A operation
         * @throws InterruptedException If an exception occured while simulating
         */
        private void simulateOperation(String operation) {
            requireNonNull(operation, "operation");

            if (isTerminated()) {
                return;
            }

            LOG.debug("Operating...");
            final int operatingTime = getProcessModel().getOperatingTime();
            getProcessModel().setVehicleState(Vehicle.State.EXECUTING);
            for (int timePassed = 0; timePassed < operatingTime && !isTerminated();
                 timePassed += simAdvanceTime) {
                Uninterruptibles.sleepUninterruptibly(ADVANCE_TIME, TimeUnit.MILLISECONDS);
                getProcessModel().getVelocityController().advanceTime(simAdvanceTime);
            }
            if (operation.equals(getProcessModel().getLoadOperation())) {
                // Update load handling devices as defined by this operation
                getProcessModel().setVehicleLoadHandlingDevices(
                        Arrays.asList(new LoadHandlingDevice(LHD_NAME, true)));
            } else if (operation.equals(getProcessModel().getUnloadOperation())) {
                getProcessModel().setVehicleLoadHandlingDevices(
                        Arrays.asList(new LoadHandlingDevice(LHD_NAME, false)));
            }
        }
    }

    /**
     * The vehicle's possible load states.
     */
    private enum LoadState {
        EMPTY,
        FULL;
    }
}
