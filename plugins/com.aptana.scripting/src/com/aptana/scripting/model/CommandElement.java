/**
 * This file Copyright (c) 2005-2010 Aptana, Inc. This program is
 * dual-licensed under both the Aptana Public License and the GNU General
 * Public license. You may elect to use one or the other of these licenses.
 * 
 * This program is distributed in the hope that it will be useful, but
 * AS-IS and WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, TITLE, or
 * NONINFRINGEMENT. Redistribution, except as permitted by whichever of
 * the GPL or APL you select, is prohibited.
 *
 * 1. For the GPL license (GPL), you can redistribute and/or modify this
 * program under the terms of the GNU General Public License,
 * Version 3, as published by the Free Software Foundation.  You should
 * have received a copy of the GNU General Public License, Version 3 along
 * with this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Aptana provides a special exception to allow redistribution of this file
 * with certain other free and open source software ("FOSS") code and certain additional terms
 * pursuant to Section 7 of the GPL. You may view the exception and these
 * terms on the web at http://www.aptana.com/legal/gpl/.
 * 
 * 2. For the Aptana Public License (APL), this program and the
 * accompanying materials are made available under the terms of the APL
 * v1.0 which accompanies this distribution, and is available at
 * http://www.aptana.com/legal/apl/.
 * 
 * You may view the GPL, Aptana's exception and additional terms, and the
 * APL in the file titled license.html at the root of the corresponding
 * plugin containing this source file.
 * 
 * Any modifications to this file must keep this entire header intact.
 */
package com.aptana.scripting.model;

import java.io.ByteArrayOutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.jruby.Ruby;
import org.jruby.RubyHash;
import org.jruby.RubyProc;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import com.aptana.core.ShellExecutable;
import com.aptana.parsing.io.SourcePrinter;
import com.aptana.scripting.Activator;
import com.aptana.scripting.ScriptLogger;
import com.aptana.scripting.ScriptUtils;
import com.aptana.scripting.ScriptingEngine;

public class CommandElement extends AbstractBundleElement
{
	private static interface InvokeUnion
	{
		String getInvoke();

		RubyProc getInvokeBlock();
	}

	private static final class Invoke implements InvokeUnion
	{
		private final String _invoke;

		private Invoke(String invoke)
		{
			this._invoke = invoke;
		}

		public String getInvoke()
		{
			return this._invoke;
		}

		public RubyProc getInvokeBlock()
		{
			return null;
		}

		public String toString()
		{
			return "invoke= " + _invoke; //$NON-NLS-1$
		}
	}

	private static final class InvokeBlock implements InvokeUnion
	{
		private final RubyProc _invokeBlock;

		private InvokeBlock(RubyProc invokeBlock)
		{
			this._invokeBlock = invokeBlock;
		}

		public String getInvoke()
		{
			return null;
		}

		public RubyProc getInvokeBlock()
		{
			return this._invokeBlock;
		}

		public String toString()
		{
			return "invoke <block>"; //$NON-NLS-1$
		}
	}

	private static final InvokeUnion NO_INVOKE = new InvokeUnion()
	{
		public String getInvoke()
		{
			return null;
		}

		public RubyProc getInvokeBlock()
		{
			return null;
		}

		public String toString()
		{
			return ""; //$NON-NLS-1$
		}
	};

	private static final InputType[] NO_TYPES = new InputType[0];
	private static final String[] NO_KEY_BINDINGS = new String[0];
	private static final String[] NO_TRIGGER_VALUES = new String[0];
	private static final String TO_ENV_METHOD_NAME = "to_env"; //$NON-NLS-1$

	private Map<Platform, InvokeUnion> _invokeUnionMap;
	private Map<Platform, String[]> _keyBindings;
	private InputType[] _inputTypes;
	private String _inputPath;
	private OutputType _outputType;
	private String _outputPath;
	private boolean _async;
	private RunType _runType;
	private Ruby _runtime;

	private IPath _workingDirectoryPath;
	private WorkingDirectoryType _workingDirectoryType;

	/**
	 * Snippet
	 * 
	 * @param path
	 */
	public CommandElement(String path)
	{
		super(path);

		this._inputTypes = NO_TYPES;
		this._outputType = OutputType.UNDEFINED;
		this._workingDirectoryType = WorkingDirectoryType.UNDEFINED;
		this._runType = Activator.getDefaultRunType();
	}

	/**
	 * createCommandContext
	 * 
	 * @return
	 */
	public CommandContext createCommandContext()
	{
		return new CommandContext(this);
	}

	/**
	 * execute
	 * 
	 * @return
	 */
	public CommandResult execute()
	{
		return this.execute(this.createCommandContext());
	}

	/**
	 * execute
	 * 
	 * @param map
	 * @return
	 */
	public CommandResult execute(CommandContext context)
	{
		CommandResult result = null;

		if (this.isExecutable())
		{
			AbstractCommandRunner job = null;

			// determine if we are running asynchronously taking the output type into account
			boolean async = (this._async && this._outputType.allowAsync());

			// set default output type, this may be changed by context.exit_with_message
			context.setOutputType(this._outputType);

			// create job based on invocation type
			if (this.isShellCommand())
			{
				job = new CommandScriptRunner(this, context);
			}
			else if (this.isBlockCommand())
			{
				// create output stream and attach to context
				context.setOutputStream(new ByteArrayOutputStream());

				BundleElement bundle = this.getOwningBundle();
				String bundleName = bundle.getDisplayName();
				job = new CommandBlockRunner(this, context, BundleManager.getInstance().getBundleLoadPaths(bundleName));
			}

			// run the job, if we have one
			if (job != null)
			{
				try
				{
					job.run("Execute '" + this.getDisplayName() + "'", this._runType, async); //$NON-NLS-1$ //$NON-NLS-2$
				}
				catch (InterruptedException e)
				{
					String message = MessageFormat.format(Messages.CommandElement_Error_Executing_Command,
							new Object[] { this.getDisplayName(), this.getPath() });

					ScriptUtils.logErrorWithStackTrace(message, e);
				}

				// get result, using a default shell if we're running async
				result = (async && this._runType != RunType.CURRENT_THREAD) ? new CommandResult(this, context) : job
						.getCommandResult();
			}
		}

		if (result != null)
		{
			// grab input type so we can report back which input was used
			String inputTypeString = (String) context.get(CommandContext.INPUT_TYPE);
			InputType inputType = InputType.get(inputTypeString);

			result.setInputType(inputType);
		}

		return result;
	}

	/**
	 * getElementName
	 */
	protected String getElementName()
	{
		return "command"; //$NON-NLS-1$
	}

	/**
	 * getInputPath
	 * 
	 * @return
	 */
	public String getInputPath()
	{
		return this._inputPath;
	}

	/**
	 * getInput
	 * 
	 * @return
	 */
	public InputType[] getInputTypes()
	{
		return this._inputTypes;
	}

	/**
	 * getInvokeUnion
	 * 
	 * @return
	 */
	private InvokeUnion getInvokeUnion()
	{
		Platform[] platforms = Platform.getCurrentPlatforms();
		InvokeUnion result = null;

		if (this._invokeUnionMap == null)
		{
			return NO_INVOKE;
		}

		for (Platform platform : platforms)
		{
			if (platform != Platform.UNDEFINED)
			{
				result = this._invokeUnionMap.get(platform);

				if (result != null)
				{
					break;
				}
			}
		}

		if (result == null)
		{
			result = this._invokeUnionMap.get(Platform.ALL);
		}
		if (result == null)
		{
			result = NO_INVOKE;
		}
		return result;
	}

	/**
	 * setInvokeUnion
	 * 
	 * @param OS
	 * @param invokeUnion
	 */
	private void setInvokeUnion(String OS, InvokeUnion invokeUnion)
	{
		Platform bindingOS = Platform.get(OS);

		if (bindingOS != Platform.UNDEFINED)
		{
			if (this._invokeUnionMap == null)
			{
				this._invokeUnionMap = new HashMap<Platform, InvokeUnion>();
			}

			this._invokeUnionMap.put(bindingOS, invokeUnion);
		}
		else
		{
			String message = MessageFormat.format(Messages.CommandElement_Unrecognized_OS, new Object[] {
					this.getPath(), OS });

			ScriptLogger.logWarning(message);
		}
	}

	/**
	 * getInvoke
	 * 
	 * @return
	 */
	public String getInvoke()
	{
		return this.getInvokeUnion().getInvoke();
	}

	/**
	 * getInvokeBlock
	 * 
	 * @return
	 */
	public RubyProc getInvokeBlock()
	{
		return this.getInvokeUnion().getInvokeBlock();
	}

	/**
	 * getKeyBinding
	 * 
	 * @return
	 */
	public String[] getKeyBindings()
	{
		Platform[] platforms = Platform.getCurrentPlatforms();
		String[] result = null;

		if (this._keyBindings == null)
		{
			return NO_KEY_BINDINGS;
		}

		for (Platform platform : platforms)
		{
			if (platform != Platform.UNDEFINED)
			{
				result = this._keyBindings.get(platform);

				if (result != null && result.length > 0)
				{
					break;
				}
			}
		}

		if (result == null)
		{
			result = this._keyBindings.get(Platform.ALL);
		}

		return result;
	}

	/**
	 * getKeySequence
	 * 
	 * @return
	 */
	public KeySequence[] getKeySequences()
	{
		String[] bindings = this.getKeyBindings();
		List<KeySequence> result = new ArrayList<KeySequence>();

		if (bindings != null && bindings.length > 0)
		{
			for (String binding : bindings)
			{
				try
				{
					// Need to convert the format
					String normalizedKeyBinding = ScriptUtils.normalizeKeyBinding(binding);
					KeySequence sequence = KeySequence.getInstance(normalizedKeyBinding);

					result.add(sequence);
				}
				catch (ParseException e)
				{
					String message = MessageFormat.format(Messages.CommandElement_Invalid_Key_Binding, new Object[] {
							binding, this.getDisplayName(), this.getPath(), e.getMessage() });

					ScriptLogger.logError(message);
				}
			}
		}
		return result.toArray(new KeySequence[result.size()]);
	}

	/**
	 * getOutputPath
	 * 
	 * @return
	 */
	public String getOutputPath()
	{
		return this._outputPath;
	}

	/**
	 * getOutputType
	 * 
	 * @return
	 */
	public String getOutputType()
	{
		return this._outputType.getName();
	}

	/**
	 * getRuntime
	 * 
	 * @return
	 */
	public Ruby getRuntime()
	{
		return this._runtime;
	}

	/**
	 * getRunType
	 * 
	 * @return
	 */
	public String getRunType()
	{
		return this._runType.getName();
	}
	
	/**
	 * Get the values associated with the specified trigger type
	 * 
	 * @param type
	 * @return
	 */
	public String[] getTriggerTypeValues(TriggerType type)
	{
		String[] result = NO_TRIGGER_VALUES;
		
		if (type != null && type != TriggerType.UNDEFINED)
		{
			String propertyName = type.getPropertyName();
			Object value = this.get(propertyName);
			
			if (value instanceof String[])
			{
				result = (String[]) value;
			}
			else if (value instanceof Object[])
			{
				Object[] objects = (Object[]) value;
				
				result = new String[objects.length];
				
				for (int i = 0; i < objects.length; i++)	
				{
					result[i] = objects[i].toString();
				}
			}
			else if (value != null)
			{
				result = new String[] { value.toString() };
			}
		}
		
		return result;
	}

	/**
	 * getWorkingDirectory
	 * 
	 * @return
	 */
	public IPath getWorkingDirectory()
	{
		switch (this._workingDirectoryType)
		{
			case PATH:
			case CURRENT_PROJECT:
				// This case is handled specially because of bundle dependencies. The App Explorer plugin will look for
				// this type and will turn it into a PATH type and set the path to the current project
				return this._workingDirectoryPath;
			case CURRENT_BUNDLE:
				return Path.fromOSString(getOwningBundle().getBundleDirectory().getAbsolutePath());
			case UNDEFINED:
			case CURRENT_FILE:
			default:
				return Path.fromOSString(this.getPath()).removeLastSegments(1);
		}
	}

	public WorkingDirectoryType getWorkingDirectoryType()
	{
		return this._workingDirectoryType;
	}

	/**
	 * isAsync
	 * 
	 * @return
	 */
	public boolean isAsync()
	{
		return this._async;
	}

	/**
	 * isBlockCommand
	 * 
	 * @return
	 */
	public boolean isBlockCommand()
	{
		return (this.getInvokeUnion().getInvokeBlock() != null);
	}

	/**
	 * isExecutable
	 * 
	 * @return
	 */
	public boolean isExecutable()
	{
		return ((this.getInvoke() != null && this.getInvoke().length() > 0) || this.getInvokeBlock() != null);
	}

	/**
	 * isShellCommand
	 * 
	 * @return
	 */
	public boolean isShellCommand()
	{
		return (this.getInvokeBlock() == null && this.getInvoke() != null && this.getInvoke().length() > 0);
	}

	/**
	 * populateEnvironment
	 * 
	 * @param contextMap
	 * @param environment
	 */
	@SuppressWarnings("deprecation")
	void populateEnvironment(Map<String, Object> contextMap, Map<String, String> environment)
	{
		environment.putAll(ShellExecutable.getEnvironment());
		for (Map.Entry<String, Object> entry : contextMap.entrySet())
		{
			Object valueObject = entry.getValue();
			String key = entry.getKey().toUpperCase();

			if (valueObject instanceof IRubyObject)
			{
				IRubyObject rubyObject = (IRubyObject) valueObject;

				if (rubyObject.respondsTo(TO_ENV_METHOD_NAME))
				{
					Ruby runtime = ScriptingEngine.getInstance().getScriptingContainer().getRuntime();
					ThreadContext threadContext = runtime.getCurrentContext();

					try
					{
						IRubyObject methodResult = rubyObject.callMethod(threadContext, TO_ENV_METHOD_NAME);

						if (methodResult instanceof RubyHash)
						{
							RubyHash environmentHash = (RubyHash) methodResult;

							for (Object hashKey : environmentHash.keySet())
							{
								environment.put(hashKey.toString(), environmentHash.get(hashKey).toString());
							}
						}
					}
					catch (RaiseException e)
					{
						String message = MessageFormat.format(Messages.CommandElement_Error_Building_Env_Variables,
								new Object[] { entry.getKey(), this.getDisplayName(), this.getPath(), e.getMessage() });

						ScriptLogger.logError(message);
						e.printStackTrace();
					}
				}
			}
			else if (valueObject instanceof EnvironmentContributor)
			{
				EnvironmentContributor contributor = (EnvironmentContributor) valueObject;
				Map<String, String> contributedEnvironment = contributor.toEnvironment();

				if (contributedEnvironment != null)
				{
					environment.putAll(contributedEnvironment);
				}
			}
			else if (valueObject != null)
			{
				environment.put(key, valueObject.toString());
			}
		}
	}

	/**
	 * printBody
	 */
	protected void printBody(SourcePrinter printer)
	{
		// output path and scope
		printer.printWithIndent("path: ").println(this.getPath()); //$NON-NLS-1$
		printer.printWithIndent("scope: ").println(this.getScope()); //$NON-NLS-1$

		// output invoke/expansion, if it is defined
		if (this.getInvoke() != null)
		{
			printer.printWithIndent("invoke: ").println(this.getInvoke()); //$NON-NLS-1$
		}

		// output invoke block, if it is defined
		if (this.getInvokeBlock() != null)
		{
			printer.printWithIndent("block: ").println(this.getInvokeBlock().to_s().asJavaString()); //$NON-NLS-1$
		}

		// output key bindings, if it is defined
		if (this._keyBindings != null && this._keyBindings.size() > 0)
		{
			printer.printlnWithIndent("keys {").increaseIndent(); //$NON-NLS-1$

			for (Map.Entry<Platform, String[]> entry : this._keyBindings.entrySet())
			{
				printer.printWithIndent(entry.getKey().getName()).print(": "); //$NON-NLS-1$

				boolean first = true;

				for (String binding : entry.getValue())
				{
					if (first == false)
					{
						printer.print(", "); //$NON-NLS-1$
					}

					printer.print(binding);

					first = false;
				}

				printer.println();
			}

			printer.decreaseIndent().printlnWithIndent("}"); //$NON-NLS-1$
		}

		// output a comma-delimited list of input types, if they are defined
		InputType[] types = this.getInputTypes();

		if (types != null && types.length > 0)
		{
			boolean first = true;

			printer.printWithIndent("input: "); //$NON-NLS-1$

			for (InputType type : types)
			{
				if (first == false)
				{
					printer.print(", "); //$NON-NLS-1$
				}

				printer.print(type.getName());

				first = false;
			}

			printer.println();
		}

		// output output type
		printer.printWithIndent("output: ").println(this._outputType.getName()); //$NON-NLS-1$

		// output a comma-delimited list of triggers, if any are defined
		String[] triggers = this.getTriggerTypeValues(TriggerType.PREFIX);

		if (triggers != null && triggers.length > 0)
		{
			boolean first = true;

			printer.printWithIndent("triggers: "); //$NON-NLS-1$

			for (String trigger : triggers)
			{
				if (first == false)
				{
					printer.print(", "); //$NON-NLS-1$
				}

				printer.print(trigger);

				first = false;
			}

			printer.println();
		}
	}

	/**
	 * setAsync
	 * 
	 * @param value
	 */
	public void setAsync(boolean value)
	{
		this._async = value;
	}

	/**
	 * setInputPath
	 * 
	 * @param path
	 */
	public void setInputPath(String path)
	{
		this._inputPath = path;
	}

	/**
	 * setInputType
	 * 
	 * @param type
	 */
	public void setInputType(InputType type)
	{
		this.setInputType(new InputType[] { type });
	}

	/**
	 * setInputType
	 * 
	 * @param types
	 */
	public void setInputType(InputType[] types)
	{
		this._inputTypes = (types == null) ? NO_TYPES : types;
	}

	/**
	 * setInputType
	 * 
	 * @param input
	 */
	public void setInputType(String input)
	{
		this.setInputType(InputType.get(input));
	}

	/**
	 * setInputType
	 * 
	 * @param types
	 */
	public void setInputType(String[] types)
	{
		InputType[] result = null;

		if (types != null)
		{
			result = new InputType[types.length];

			for (int i = 0; i < types.length; i++)
			{
				result[i] = InputType.get(types[i]);
			}
		}

		this.setInputType(result);
	}

	/**
	 * setInvoke
	 * 
	 * @param invoke
	 */
	public void setInvoke(String invoke)
	{
		this.setInvoke(Platform.ALL.getName(), invoke);
	}

	/**
	 * setInvokeBlock
	 * 
	 * @param block
	 */
	public void setInvokeBlock(RubyProc block)
	{
		this.setInvokeBlock(Platform.ALL.getName(), block);
	}

	/**
	 * setInvoke
	 * 
	 * @param invoke
	 */
	public void setInvoke(String OS, String invoke)
	{
		InvokeUnion invokeUnion = new Invoke(invoke);
		setInvokeUnion(OS, invokeUnion);
	}

	/**
	 * setInvokeBlock
	 * 
	 * @param block
	 */
	public void setInvokeBlock(String OS, RubyProc block)
	{
		InvokeUnion invokeUnion = new InvokeBlock(block);
		setInvokeUnion(OS, invokeUnion);
		this.setRuntime((block != null) ? block.getRuntime() : null);
	}

	/**
	 * setKeyBinding
	 * 
	 * @param keyBinding
	 */
	public void setKeyBinding(String OS, String keyBinding)
	{
		if (keyBinding != null && keyBinding.length() > 0)
		{
			this.setKeyBindings(OS, new String[] { keyBinding });
		}
		else
		{
			String message = MessageFormat.format(Messages.CommandElement_Undefined_Key_Binding, new Object[] { this
					.getPath() });

			ScriptLogger.logWarning(message);
		}
	}

	/**
	 * setKeyBindings
	 * 
	 * @param OS
	 * @param keyBindings
	 */
	public void setKeyBindings(String OS, String[] keyBindings)
	{
		Platform bindingOS = Platform.get(OS);

		if (bindingOS != Platform.UNDEFINED)
		{
			if (this._keyBindings == null)
			{
				this._keyBindings = new HashMap<Platform, String[]>();
			}
			
			// Force each string to be uppercase, http://aptana.lighthouseapp.com/projects/45260/tickets/393
			int i = 0;
			String[] uppercase = new String[keyBindings.length];
			for(String binding : keyBindings)
			{
				uppercase[i++] = binding.toUpperCase();
			}
			this._keyBindings.put(bindingOS, uppercase);
		}
		else
		{
			String message = MessageFormat.format(Messages.CommandElement_Unrecognized_OS, new Object[] {
					this.getPath(), OS });

			ScriptLogger.logWarning(message);
		}
	}

	/**
	 * setOutputPath
	 * 
	 * @param path
	 */
	public void setOutputPath(String path)
	{
		this._outputPath = path;
	}

	/**
	 * setOutputType
	 * 
	 * @param type
	 */
	public void setOutputType(OutputType type)
	{
		this._outputType = type;
	}

	/**
	 * setOutput
	 * 
	 * @param output
	 */
	public void setOutputType(String output)
	{
		this._outputType = OutputType.get(output);
	}

	/**
	 * setRunType
	 * 
	 * @param type
	 */
	public void setRunType(String type)
	{
		this._runType = RunType.get(type);
	}

	/**
	 * setRuntime
	 * 
	 * @param object
	 */
	public void setRuntime(IRubyObject object)
	{
		this.setRuntime((object != null) ? object.getRuntime() : null);
	}

	/**
	 * setRuntime
	 * 
	 * @param object
	 */
	public void setRuntime(Ruby runtime)
	{
		this._runtime = runtime;
	}

	/**
	 * setRunType
	 * 
	 * @param type
	 */
	public void setRunType(RunType type)
	{
		this._runType = type;
	}

	/**
	 * setTrigger
	 * 
	 * @param trigger
	 */
	public void setTrigger(String type, String[] values)
	{
		TriggerType triggerType = TriggerType.get(type);
		
		if (triggerType != TriggerType.UNDEFINED && values != null && values.length > 0)
		{
			String propertyName = triggerType.getPropertyName();
			
			this.put(propertyName, values);
		}
	}

	/**
	 * setOutputPath
	 * 
	 * @param path
	 */
	public void setWorkingDirectoryPath(IPath path)
	{
		this._workingDirectoryPath = path;
	}

	/**
	 * setWorkingDirectoryType
	 * 
	 * @param workingDirectory
	 */
	public void setWorkingDirectoryType(String workingDirectory)
	{
		this._workingDirectoryType = WorkingDirectoryType.get(workingDirectory);
	}

	/**
	 * setWorkingDirectoryType
	 * 
	 * @param type
	 */
	public void setWorkingDirectoryType(WorkingDirectoryType type)
	{
		this._workingDirectoryType = type;
	}

	/**
	 * Return the environment based on the current context.
	 * 
	 * @return environment map
	 */
	public Map<String, String> getEnvironment()
	{
		CommandContext commandContext = createCommandContext();

		// Get the process's environment
		Map<String, String> environment = new LinkedHashMap<String, String>(new ProcessBuilder().environment());
		populateEnvironment(commandContext.getMap(), environment);

		return environment;
	}
}
