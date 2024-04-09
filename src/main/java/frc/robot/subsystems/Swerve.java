package frc.robot.subsystems;

import frc.lib.CtreConfigs;
import frc.lib.Constants;
import frc.lib.config.krakenTalonConstants;
import frc.robot.classes.Pigeon2Handler;
import frc.robot.classes.krakenFalcon.SwerveModule;
import frc.robot.classes.krakenFalcon.SwerveModuleKrakenFalcon;

import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveDriveOdometry;
import edu.wpi.first.math.kinematics.SwerveModulePosition;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.ctre.phoenix6.configs.Pigeon2Configuration;
import com.ctre.phoenix6.hardware.Pigeon2;
import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.util.HolonomicPathFollowerConfig;
import com.pathplanner.lib.util.PIDConstants;
import com.pathplanner.lib.util.ReplanningConfig;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.SubsystemBase;

public class  Swerve extends SubsystemBase {
    public SwerveDriveOdometry swerveOdometry;
    public SwerveModule[] mSwerveMods;
    public Pigeon2 gyro;

    private ChassisSpeeds latestSpeeds = new ChassisSpeeds();
    private Consumer<ChassisSpeeds> setPathPlannerSpeed;

    /**
     * Default constructor uses SwerveModuleTalonNeo
     */
    public Swerve(CtreConfigs ctreConfigs, Pigeon2 gyro, Consumer<ChassisSpeeds> robotSpeeds) {
        this(new SwerveModule[]{
                new SwerveModuleKrakenFalcon(ctreConfigs, Constants.mod3backrightConfig, 3),
                new SwerveModuleKrakenFalcon(ctreConfigs, Constants.mod1frontrightConfig, 1),
                new SwerveModuleKrakenFalcon(ctreConfigs, Constants.mod2backleftConfig, 2),
                new SwerveModuleKrakenFalcon(ctreConfigs, Constants.mod0frontleftConfig, 0)
        }, gyro, robotSpeeds);
    }

    /**
     * Constructor that allows custom SwerveModules
     */
    public Swerve(SwerveModule[] modules, Pigeon2 gyro, Consumer<ChassisSpeeds> robotSpeeds) {
        this.mSwerveMods = modules;
        this.gyro = gyro;
        this.setPathPlannerSpeed = robotSpeeds;

        gyro.getConfigurator().apply(new Pigeon2Configuration());
        gyro.setYaw(0);
        swerveOdometry = new SwerveDriveOdometry(krakenTalonConstants.Swerve.driveTrainConfig.kinematics, getGyroYaw(), getModulePositions());

        // TODO: Get real values for these variables
        double maxModuleSpeed = krakenTalonConstants.Swerve.maxSpeed;
        double driveBaseRadius = krakenTalonConstants.Swerve.swerveRadius;
        ReplanningConfig replanningConfig = new ReplanningConfig();
        HolonomicPathFollowerConfig pathFollowerConfig = new HolonomicPathFollowerConfig(
            new PIDConstants(0.1,0,0), 
            new PIDConstants(0.1,0,0), 
            maxModuleSpeed, 
            driveBaseRadius, 
            replanningConfig
        );

        AutoBuilder.configureHolonomic(
            () -> {
                // TODO: return swerve pose
                return this.getPose();
            },
            (pose) -> {
                this.setPose(pose);
            },
            () -> {
                // Get the latest robot-relative ChassisSpeeds of the Swerve
                return this.latestSpeeds;
            }, 
            // setPathPlannerSpeed,
            (pathSpeeds) -> {
                // TODO: Set the input component for path planner to this ChassisSpeed value
                setPathPlannerSpeed.accept(pathSpeeds);
            },
            pathFollowerConfig,
            () -> {
                // Boolean supplier that controls when the path will be mirrored for the red alliance
                // This will flip the path being followed to the red side of the field.
                // THE ORIGIN WILL REMAIN ON THE BLUE SIDE
                var alliance = DriverStation.getAlliance();
                if (alliance.isPresent()) {
                    return alliance.get() == DriverStation.Alliance.Red;
                }
                return false;
            }, 
            // For our blended mode, we need the Swerve subsystem to be running the HybridSwerve
            // command. Instead of giving PathPlanner control of our subsystem, we give it control
            // of a dummy subsystem that does nothing, and we simply wire the inputs and outputs
            // of the PathPlanner into our blended control
            new DummySwerve()
        );
    }

    public ChassisSpeeds getLatestSpeeds() {
        return latestSpeeds;
    }

    public void drive(Translation2d translation, double rotation, boolean fieldRelative, boolean isOpenLoop) {
        ChassisSpeeds speeds = fieldRelative ? ChassisSpeeds.fromFieldRelativeSpeeds(
                translation.getX(),
                translation.getY(),
                rotation,
                getHeading()
        ) : new ChassisSpeeds(
                -translation.getX(),
                -translation.getY(),
                rotation);

        driveChassisSpeeds(speeds, isOpenLoop);
    }

    public void driveChassisSpeeds(ChassisSpeeds speeds, boolean isOpenLoop) {
        this.latestSpeeds = speeds;
        SwerveModuleState[] swerveModuleStates = krakenTalonConstants.Swerve.driveTrainConfig.kinematics.toSwerveModuleStates(speeds);
        SwerveDriveKinematics.desaturateWheelSpeeds(swerveModuleStates, krakenTalonConstants.Swerve.maxSpeed);

        for (int i = 0; i < mSwerveMods.length; i++) {
            SwerveModule mod = mSwerveMods[i];
            mod.setDesiredState(swerveModuleStates[i], isOpenLoop);
        }
    }

    public void debugSetDriveSpeed(int module, double speed) {
        mSwerveMods[module].debugSetDriveSpeed(speed);
    }

    public void debugSetSteeringSpeed(int module, double speed) {
        mSwerveMods[module].debugSetSteeringSpeed(speed);
    }

    /* Used by SwerveControllerCommand in Auto */
    public void setModuleStates(SwerveModuleState[] desiredStates) {
        SwerveDriveKinematics.desaturateWheelSpeeds(desiredStates, krakenTalonConstants.Swerve.maxSpeed);

        for (int i = 0; i < mSwerveMods.length; i++) {
            SwerveModule mod = mSwerveMods[i];
            mod.setDesiredState(desiredStates[i], false);
        }
    }

    public SwerveModuleState[] getModuleStates() {
        SwerveModuleState[] states = new SwerveModuleState[4];
        for (int i = 0; i < mSwerveMods.length; i++) {
            SwerveModule mod = mSwerveMods[i];
            states[i] = mod.getState();
        }
        return states;
    }

    public SwerveModulePosition[] getModulePositions() {
        SwerveModulePosition[] positions = new SwerveModulePosition[4];
        for (int i = 0; i < mSwerveMods.length; i++) {
            SwerveModule mod = mSwerveMods[i];
            positions[i] = mod.getPosition();
        }
        return positions;
    }

    public Pose2d getPose() {
        Pose2d pose = swerveOdometry.getPoseMeters();
        return pose;
    }

    public void setPose(Pose2d pose) {
        swerveOdometry.resetPosition(getGyroYaw(), getModulePositions(), pose);
    }

    public Rotation2d getHeading() {
        return getPose().getRotation();
    }

    public void setHeading(Rotation2d heading) {
        swerveOdometry.resetPosition(getGyroYaw(), getModulePositions(), new Pose2d(getPose().getTranslation(), heading));
    }

    public void zeroHeading() {
        swerveOdometry.resetPosition(Rotation2d.fromDegrees(getGyroYaw().getDegrees()+180), getModulePositions(), new Pose2d(getPose().getTranslation(), new Rotation2d()));
    }

    public Rotation2d getGyroYaw() {
        return Rotation2d.fromDegrees(-gyro.getYaw().getValue());
    }

    public void resetModulesToAbsolute() {
        for (int i = 0; i < mSwerveMods.length; i++) {
            SwerveModule mod = mSwerveMods[i];
            mod.resetToAbsolute();
        }
    }

    public void zeroEncoders(){
        for(SwerveModule mod : mSwerveMods){
            mod.zeroEncoders();
        }
    }

    @Override
    public void periodic() {
        swerveOdometry.update(getGyroYaw(), getModulePositions());
        SmartDashboard.putNumber("swerveperiodic", 0);
    }
}