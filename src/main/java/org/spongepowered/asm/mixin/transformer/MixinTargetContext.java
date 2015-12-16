/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.mixin.transformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.spongepowered.asm.lib.Handle;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.*;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.SoftOverride;
import org.spongepowered.asm.mixin.injection.struct.ReferenceMapper;
import org.spongepowered.asm.mixin.injection.struct.Target;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Traversal;
import org.spongepowered.asm.util.ASMHelper;
import org.spongepowered.asm.util.Constants;


/**
 * This object keeps track of data for applying a mixin to a specific target
 * class <em>during</em> a mixin application. This is a single-use object which
 * acts as both a handle information we need when applying the mixin (such as
 * the actual mixin ClassNode and the target ClassNode) and a gateway to
 * context-sensitive operations such as re-targetting method and field accesses
 * in the mixin to the appropriate members in the target class hierarchy. 
 */
public class MixinTargetContext implements IReferenceMapperContext {

    /**
     * Mixin info
     */
    private final MixinInfo mixin;
    
    /**
     * Tree
     */
    private final ClassNode classNode;
    
    /**
     * 
     */
    private final ClassNode targetClass;
    
    /**
     * Target ClassInfo
     */
    private final ClassInfo targetClassInfo;

    /**
     * Shadow method list
     */
    private final List<MethodNode> shadowMethods = new ArrayList<MethodNode>();

    /**
     * Shadow field list
     */
    private final List<FieldNode> shadowFields = new ArrayList<FieldNode>();

    /**
     * Information about methods in the target class, used to keep track of
     * transformations we apply
     */
    private final Map<String, Target> targetMethods = new HashMap<String, Target>();
    
    /**
     * True if this mixin inherits from a mixin at any point in its hierarchy 
     */
    private final boolean inheritsFromMixin;
    
    /**
     * True if this mixin's superclass is detached from the target superclass 
     */
    private final boolean detachedSuper;
    
    /**
     * Minimum class version required to apply this mixin, target class will be
     * upgraded if the version is below this value
     */
    private int minRequiredClassVersion = CompatibilityLevel.JAVA_6.classVersion();

    /**
     * ctor
     * 
     * @param mixin Mixin information
     * @param classNode Mixin classnode
     * @param target target class
     */
    MixinTargetContext(MixinInfo mixin, ClassNode classNode, ClassNode target) {
        this.mixin = mixin;
        this.classNode = classNode;
        this.targetClass = target;
        this.targetClassInfo = ClassInfo.forName(target.name);
        this.inheritsFromMixin = mixin.getClassInfo().hasMixinInHierarchy() || this.targetClassInfo.hasMixinTargetInHierarchy();
        this.detachedSuper = !this.classNode.superName.equals(this.targetClass.superName);
        this.requireVersion(classNode.version);
    }
    
    /**
     * @param method
     */
    void addShadowMethod(MethodNode method) {
        this.shadowMethods.add(method);
    }
    
    /**
     * @param field
     */
    void addShadowField(FieldNode field) {
        this.shadowFields.add(field);
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return this.mixin.toString();
    }

    /**
     * Get the mixin tree
     * 
     * @return mixin tree
     */
    public ClassNode getClassNode() {
        return this.classNode;
    }
    
    /**
     * Get the mixin class name
     * 
     * @return the mixin class name
     */
    public String getClassName() {
        return this.mixin.getClassName();
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.transformer.IReferenceMapperContext
     *      #getClassRef()
     */
    @Override
    public String getClassRef() {
        return this.mixin.getClassRef();
    }

    /**
     * Get the target class reference
     * 
     * @return the reference of the target class (only valid on single-target
     *      mixins)
     */
    public String getTargetClassRef() {
        return this.targetClass.name;
    }
    
    /**
     * Get the target class
     * 
     * @return the target class
     */
    public ClassNode getTargetClass() {
        return this.targetClass;
    }
    
    /**
     * Get the target classinfo
     * 
     * @return the target class info 
     */
    public ClassInfo getTargetClassInfo() {
        return this.targetClassInfo;
    }
    
    /**
     * Get the minimum required class version for this mixin
     */
    public int getMinRequiredClassVersion() {
        return this.minRequiredClassVersion;
    }

    /**
     * Find the corresponding class type for the supplied mixin class in this
     * mixin target's hierarchy
     * 
     * @param mixin Mixin class to discover
     * @return Transformed
     */
    public ClassInfo findRealType(ClassInfo mixin) {
        if (mixin == this.mixin.getClassInfo()) {
            return this.targetClassInfo;
        }
        
        ClassInfo type = this.targetClassInfo.findCorrespondingType(mixin);
        if (type == null) {
            throw new InvalidMixinException(this, "Resolution error: unable to find corresponding type for "
                    + mixin + " in hierarchy of " + this.targetClassInfo);
        }
        
        return type;
    }

    /**
     * Handles "re-parenting" the method supplied, changes all references to the
     * mixin class to refer to the target class (for field accesses and method
     * invokations) and also handles fixing up the targets of INVOKESPECIAL
     * opcodes for mixins with detached targets.
     * 
     * @param method Method to transform
     */
    public void transformMethod(MethodNode method) {
        this.validateMethod(method);
        this.transformDescriptor(method);
        
        AbstractInsnNode lastInsn = null;
        for (Iterator<AbstractInsnNode> iter = method.instructions.iterator(); iter.hasNext();) {
            AbstractInsnNode insn = iter.next();

            if (insn instanceof MethodInsnNode) {
                this.transformMethodRef(method, iter, new MemberRef.Method((MethodInsnNode)insn));
            } else if (insn instanceof FieldInsnNode) {
                this.transformFieldRef(method, iter, new MemberRef.Field((FieldInsnNode)insn));
            } else if (insn instanceof TypeInsnNode) {
                this.transformTypeNode(method, iter, (TypeInsnNode)insn, lastInsn);
            } else if (insn instanceof LdcInsnNode) {
                this.transformConstantNode(method, iter, (LdcInsnNode)insn);
            } else if (insn instanceof InvokeDynamicInsnNode) {
                this.transformInvokeDynamicNode(method, iter, (InvokeDynamicInsnNode)insn);
            }
            
            lastInsn = insn;
        }
    }

    /**
     * Pre-flight checks on a method to be transformed, checks the validity of
     * {@link SoftOverride} annotations and any other required validation tasks
     * 
     * @param method Method node to validate
     */
    private void validateMethod(MethodNode method) {
        // Any method tagged with @SoftOverride must have an implementation visible from 
        if (ASMHelper.getInvisibleAnnotation(method, SoftOverride.class) != null) {
            ClassInfo.Method superMethod = this.targetClassInfo.findMethodInHierarchy(method.name, method.desc, false, Traversal.SUPER);
            if (superMethod == null || !superMethod.isInjected()) {
                throw new InvalidMixinException(this, "Mixin method " + method.name + method.desc + " is tagged with @SoftOverride but no "
                        + "valid method was found in superclasses of " + this.targetClass.name);
            }
        }
    }

    /**
     * Transforms a method invocation/reference in the method. Updates static
     * and dynamic bindings.
     * 
     * @param method Method being processed
     * @param iter Insn interator
     * @param methodRef Method reference to transform
     */
    private void transformMethodRef(MethodNode method, Iterator<AbstractInsnNode> iter, MemberRef methodRef) {
        this.transformDescriptor(methodRef);
        
        if (methodRef.getOwner().equals(this.getClassRef())) {
            methodRef.setOwner(this.targetClass.name);
        } else if ((this.detachedSuper || this.inheritsFromMixin)) {
            if (methodRef.getOpcode() == Opcodes.INVOKESPECIAL) {
                this.updateStaticBinding(method, methodRef);
            } else if (methodRef.getOpcode() == Opcodes.INVOKEVIRTUAL && ClassInfo.forName(methodRef.getOwner()).isMixin()) {
                this.updateDynamicBinding(method, methodRef);
            }
        }
    }

    /**
     * Transforms field access/reference in the method. Handles imaginary super
     * accesses and converts them to real super-invocations and rewrites field
     * accesses which refer to mixin or supermixin classes to their relevant
     * targets.
     * 
     * @param method Method being processed
     * @param iter Insn interator
     * @param fieldRef Field Reference to transform
     */
    private void transformFieldRef(MethodNode method, Iterator<AbstractInsnNode> iter, MemberRef fieldRef) {
        if (Constants.IMAGINARY_SUPER.equals(fieldRef.getName())) {
            if (fieldRef instanceof MemberRef.Field) {
                this.processImaginarySuper(method, ((MemberRef.Field) fieldRef).insn);
                iter.remove();
            } else {
                throw new InvalidMixinException(this.mixin, "Cannot call imaginary super from method handle.");
            }
        }
        
        this.transformDescriptor(fieldRef);
        
        if (fieldRef.getOwner().equals(this.getClassRef())) {
            fieldRef.setOwner(this.targetClass.name);
        } else {
            ClassInfo fieldOwner = ClassInfo.forName(fieldRef.getOwner());
            if (fieldOwner.isMixin()) {
                ClassInfo actualOwner = this.targetClassInfo.findCorrespondingType(fieldOwner);
                fieldRef.setOwner(actualOwner != null ? actualOwner.getName() : this.targetClass.name);
            }
        }
    }

    /**
     * Transforms type operations (eg. cast, instanceof) in the method being
     * processed. Changes references to mixin classes to that of the appropriate
     * class for this context.
     * 
     * @param method Method being processed
     * @param iter Insn interator
     * @param typeInsn Insn to transform
     * @param lastNode Last insn in the method
     */
    private void transformTypeNode(MethodNode method, Iterator<AbstractInsnNode> iter, TypeInsnNode typeInsn, AbstractInsnNode lastNode) {
        if (typeInsn.getOpcode() == Opcodes.CHECKCAST
                && typeInsn.desc.equals(this.targetClass.name)
                && lastNode.getOpcode() == Opcodes.ALOAD
                && ((VarInsnNode)lastNode).var == 0) {
            iter.remove();
            return;
        }
        
        if (typeInsn.desc.equals(this.getClassRef())) {
            typeInsn.desc = this.targetClass.name;
        }
        
        this.transformDescriptor(typeInsn);
    }

    /**
     * Transforms class literals and method handle loads in the method being
     * processed.
     * 
     * @param method Method being processed
     * @param iter Insn interator
     * @param ldcInsn Insn to transform
     */
    private void transformConstantNode(MethodNode method, Iterator<AbstractInsnNode> iter, LdcInsnNode ldcInsn) {
        ldcInsn.cst = this.transformConstant(method, iter, ldcInsn.cst);
    }

    /**
     * Transforms a invoke dynamic instruction in the method being processed.
     *
     * @param method Method being processed
     * @param iter Insn interator
     * @param dynInsn Insn to transform
     */
    private void transformInvokeDynamicNode(MethodNode method, Iterator<AbstractInsnNode> iter, InvokeDynamicInsnNode dynInsn) {
        this.requireVersion(Opcodes.V1_7);
        dynInsn.desc = this.transformMethodDescriptor(dynInsn.desc);
        dynInsn.bsm = this.transformHandle(method, iter, dynInsn.bsm);
        for (int i = 0; i < dynInsn.bsmArgs.length; i++) {
            dynInsn.bsmArgs[i] = this.transformConstant(method, iter, dynInsn.bsmArgs[i]);
        }
    }

    /**
     * Transforms a constant in the constant pool.
     *
     * @param method Method being processed
     * @param iter Insn interator
     * @param constant Consatnt pool entry
     * @return Transformed constant
     */
    private Object transformConstant(MethodNode method, Iterator<AbstractInsnNode> iter, Object constant) {
        if (constant instanceof Type) {
            Type type = (Type)constant;
            String desc = this.transformDescriptor(type);
            if (!type.toString().equals(desc)) {
                return Type.getType(desc);
            }
            return constant;
        } else if (constant instanceof Handle) {
            return this.transformHandle(method, iter, (Handle)constant);
        }
        return constant;
    }

    /**
     * Transforms a method handle that is referenced from the method being
     * processed.
     *
     * @param method Method being processed
     * @param iter Insn interator
     * @param handle Handle to transform
     */
    private Handle transformHandle(MethodNode method, Iterator<AbstractInsnNode> iter, Handle handle) {
        MemberRef.Handle memberRef = new MemberRef.Handle(handle);
        if (memberRef.isField()) {
            this.transformFieldRef(method, iter, memberRef);
        } else {
            this.transformMethodRef(method, iter, memberRef);
        }
        return memberRef.getMethodHandle();
    }

    /**
     * Handle "imaginary super" invokations, these are invokations in
     * non-derived mixins for accessing methods known to exist in a supermixin
     * which is not directly inherited by this mixix. The method can only call
     * its <b>own</b> super-implmentation and the methd must also be tagged with
     * {@link SoftOverride} to indicate that the method must exist in a super
     * class.
     * 
     * @param method Method being processed
     * @param fieldInsn the GETFIELD insn which access the pseudo-field which is
     *      used as a handle to the superclass
     */
    private void processImaginarySuper(MethodNode method, FieldInsnNode fieldInsn) {
        if (fieldInsn.getOpcode() != Opcodes.GETFIELD) {
            if (Constants.INIT.equals(method.name)) {
                throw new InvalidMixinException(this, "Illegal imaginary super declaration: field " + fieldInsn.name
                        + " must not specify an initialiser");
            }
            
            throw new InvalidMixinException(this, "Illegal imaginary super access: found " + ASMHelper.getOpcodeName(fieldInsn.getOpcode())
                    + " opcode in " + method.name + method.desc);
        }
        
        if ((method.access & Opcodes.ACC_PRIVATE) != 0 || (method.access & Opcodes.ACC_STATIC) != 0) {
            throw new InvalidMixinException(this, "Illegal imaginary super access: method " + method.name + method.desc
                    + " is private or static");
        }
        
        if (ASMHelper.getInvisibleAnnotation(method, SoftOverride.class) == null) {
            throw new InvalidMixinException(this, "Illegal imaginary super access: method " + method.name + method.desc
                    + " is not decorated with @SoftOverride");
        }
        
        for (Iterator<AbstractInsnNode> methodIter = method.instructions.iterator(method.instructions.indexOf(fieldInsn)); methodIter.hasNext();) {
            AbstractInsnNode insn = methodIter.next();
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode methodNode = (MethodInsnNode)insn;
                if (methodNode.owner.equals(this.getClassRef()) && methodNode.name.equals(method.name) && methodNode.desc.equals(method.desc)) {
                    methodNode.setOpcode(Opcodes.INVOKESPECIAL);
                    this.updateStaticBinding(method, new MemberRef.Method(methodNode));
                    return;
                }
            }
        }
        
        throw new InvalidMixinException(this, "Illegal imaginary super access: could not find INVOKE for " + method.name + method.desc);
    }

    /**
     * Update INVOKESPECIAL opcodes to target the topmost class in the hierarchy
     * which contains the specified method.
     * 
     * @param method Method containing the instruction
     * @param methodRef Unbound reference to the method
     */
    private void updateStaticBinding(MethodNode method, MemberRef methodRef) {
        this.updateBinding(method, methodRef, Traversal.SUPER);
    }

    /**
     * Update INVOKEVIRTUAL opcodes to target the topmost class in the hierarchy
     * which contains the specified method.
     * 
     * @param method Method containing the instruction
     * @param methodRef Unbound reference to the method
     */
    private void updateDynamicBinding(MethodNode method, MemberRef methodRef) {
        this.updateBinding(method, methodRef, Traversal.ALL);
    }
    
    private void updateBinding(MethodNode method, MemberRef methodRef, Traversal traversal) {
        if (Constants.INIT.equals(method.name) || methodRef.getOwner().equals(this.targetClass.name) || this.targetClass.name.startsWith("<")) {
            return;
        }
        
        ClassInfo.Method superMethod = this.targetClassInfo.findMethodInHierarchy(methodRef.getName(), methodRef.getDesc(),
                traversal == Traversal.ALL, traversal);
        if (superMethod != null) {
            if (superMethod.getOwner().isMixin()) {
                throw new InvalidMixinException(this, "Invalid " + methodRef + " in " + this + " resolved " + superMethod.getOwner()
                        + " but is mixin.");
            }
            methodRef.setOwner(superMethod.getImplementor().getName());
        } else if (ClassInfo.forName(methodRef.getOwner()).isMixin()) {
            throw new MixinTransformerError("Error resolving " + methodRef + " in " + this);
        }
    }
    
    /**
     * Transforms a field descriptor in the context of this mixin target
     * 
     * @param field Field node to transform
     */
    public void transformDescriptor(FieldNode field) {
        if (!this.inheritsFromMixin) {
            return;
        }
        field.desc = this.transformSingleDescriptor(field.desc, false);
    }
    
    /**
     * Transforms a method descriptor in the context of this mixin target
     * 
     * @param method Method node to transform
     */
    public void transformDescriptor(MethodNode method) {
        if (!this.inheritsFromMixin) {
            return;
        }
        method.desc = this.transformMethodDescriptor(method.desc);
    }

    /**
     * Transforms a method or field reference descriptor in the context of this
     * mixin target
     * 
     * @param member Reference to the method or field
     */
    public void transformDescriptor(MemberRef member) {
        if (!this.inheritsFromMixin) {
            return;
        }
        if (member.isField()) {
            member.setDesc(this.transformSingleDescriptor(member.getDesc(), false));
        } else {
            member.setDesc(this.transformMethodDescriptor(member.getDesc()));
        }
    }
    
    /**
     * Transforms a type insn descriptor in the context of this mixin target
     * 
     * @param typeInsn Type instruction node to transform
     */
    public void transformDescriptor(TypeInsnNode typeInsn) {
        if (!this.inheritsFromMixin) {
            return;
        }
        typeInsn.desc = this.transformSingleDescriptor(typeInsn.desc, true);
    }

    private String transformDescriptor(Type type) {
        if (type.getSort() == Type.METHOD) {
            return this.transformMethodDescriptor(type.getDescriptor());
        }
        return this.transformSingleDescriptor(type);
    }
    
    private String transformSingleDescriptor(Type type) {
        if (type.getSort() < Type.ARRAY) {
            return type.toString();
        }

        return this.transformSingleDescriptor(type.toString(), false);
    }
    
    private String transformSingleDescriptor(String desc, boolean isObject) {
        String type = desc;
        while (type.startsWith("[") || type.startsWith("L")) {
            if (type.startsWith("[")) {
                type = type.substring(1);
                continue;
            }
            type = type.substring(1, type.indexOf(";"));
            isObject = true;
        }
        
        if (!isObject) {
            return desc;
        }
        
        ClassInfo typeInfo = ClassInfo.forName(type);
        
        if (!typeInfo.isMixin()) {
            return desc;
        }
        
        return desc.replace(type, this.findRealType(typeInfo).toString());
    }
    
    private String transformMethodDescriptor(String desc) {
        StringBuilder newDesc = new StringBuilder();
        newDesc.append('(');
        for (Type arg : Type.getArgumentTypes(desc)) {
            newDesc.append(this.transformSingleDescriptor(arg));
        }
        return newDesc.append(')').append(this.transformSingleDescriptor(Type.getReturnType(desc))).toString();
    }

    /**
     * Get a target method handle from the target class
     * 
     * @param method method to get a target handle for
     * @return new or existing target handle for the supplied method
     */
    public Target getTargetMethod(MethodNode method) {
        if (!this.targetClass.methods.contains(method)) {
            throw new IllegalArgumentException("Invalid target method supplied to getTargetMethod()");
        }
        
        String targetName = method.name + method.desc;
        Target target = this.targetMethods.get(targetName);
        if (target == null) {
            target = new Target(method);
            this.targetMethods.put(targetName, target);
        }
        return target;
    }
    
    /**
     * Mark this mixin as requiring the specified class version in the context
     * of the current target
     * 
     * @param version version to require
     */
    protected void requireVersion(int version) {
        this.minRequiredClassVersion = Math.max(this.minRequiredClassVersion, version);
        
        // This validation is done on the mixin beforehand, however it's still
        // possible that an upstream transformer can inject java 7 instructions
        // without updating the class version.
        if (version > MixinEnvironment.getCompatibilityLevel().classVersion()) {
            throw new InvalidMixinException(this, "Unsupported mixin class version " + version);
        }
    }

    /**
     * Get the mixin info for this mixin
     */
    MixinInfo getInfo() {
        return this.mixin;
    }

    /**
     * Get the mixin priority
     * 
     * @return the priority (only meaningful in relation to other mixins)
     */
    public int getPriority() {
        return this.mixin.getPriority();
    }

    /**
     * Get all interfaces for this mixin
     * 
     * @return mixin interfaces
     */
    public Set<String> getInterfaces() {
        return this.mixin.getInterfaces();
    }
    
    /**
     * Get shadow methods in this mixin
     * 
     * @return shadow methods in the mixin
     */
    public List<MethodNode> getShadowMethods() {
        return this.shadowMethods;
    }

    /**
     * Get methods to mixin
     * 
     * @return non-shadow methods in the mixin
     */
    public List<MethodNode> getMethods() {
        return this.classNode.methods;
    }
    
    /**
     * Get shadow fields in this mixin
     * 
     * @return shadow fields in the mixin
     */
    public List<FieldNode> getShadowFields() {
        return this.shadowFields;
    }
    
    /**
     * Get fields to mixin
     * 
     * @return non-shadow fields in the mixin
     */
    public List<FieldNode> getFields() {
        return this.classNode.fields;
    }

    /**
     * Get the logging level for this mixin
     * 
     * @return the logging level
     */
    public Level getLoggingLevel() {
        return this.mixin.getLoggingLevel();
    }

    /**
     * Get whether to propogate the source file attribute from a mixin onto the
     * target class
     * 
     * @return true if the sourcefile property should be set on the target class
     */
    public boolean shouldSetSourceFile() {
        return this.mixin.getParent().shouldSetSourceFile();
    }

    /**
     * Return the source file name for the mixin
     * 
     * @return mixin source file
     */
    public String getSourceFile() {
        return this.classNode.sourceFile;
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.transformer.IReferenceMapperContext
     *      #getReferenceMapper()
     */
    @Override
    public ReferenceMapper getReferenceMapper() {
        return this.mixin.getParent().getReferenceMapper();
    }

    /**
     * Called immediately before the mixin is applied to targetClass
     * 
     * @param transformedName Target class's transformed name
     * @param targetClass Target class
     */
    public void preApply(String transformedName, ClassNode targetClass) {
        this.mixin.preApply(transformedName, targetClass);
    }

    /**
     * Called immediately after the mixin is applied to targetClass
     * 
     * @param transformedName Target class's transformed name
     * @param targetClass Target class
     */
    public void postApply(String transformedName, ClassNode targetClass) {
        this.mixin.postApply(transformedName, targetClass);
    }

}
