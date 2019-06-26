package org.eclipse.jetty.plus.webapp;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;

import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;

public class DecoratingListener implements ServletContextAttributeListener
{
    private static final Logger LOG = Log.getLogger(DecoratingListener.class);
    private static final MethodType decorateType;
    private static final MethodType destroyType;

    static
    {
        try
        {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            decorateType = MethodType.methodType(Object.class, Object.class);
            destroyType = MethodType.methodType(Void.TYPE, Object.class);
            // Ensure we have a match
            lookup.findVirtual(Decorator.class, "decorate", decorateType);
            lookup.findVirtual(Decorator.class, "destroy", destroyType);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    private final WebAppContext _context;
    private final String _attributeName;
    private Decorator _decorator;

    public DecoratingListener(WebAppContext context)
    {
        this(context, null);
    }

    public DecoratingListener(WebAppContext context, String attributeName)
    {
        _context = context;
        _attributeName = attributeName == null ? DecoratingListener.class.getPackageName() + ".Decorator" : attributeName;
    }

    private Decorator asDecorator(Object object)
    {
        if (object == null)
            return null;
        if (object instanceof Decorator)
            return (Decorator)object;

        try
        {
            Class<?> clazz = object.getClass();

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            final MethodHandle decorate = lookup.findVirtual(clazz, "decorate", decorateType);
            final MethodHandle destroy = lookup.findVirtual(clazz, "destroy", destroyType);
            return new Decorator()
            {
                @Override
                public <T> T decorate(T o)
                {
                    try
                    {
                        return (T)decorate.invoke(o);
                    }
                    catch (Throwable t)
                    {
                        throw new RuntimeException(t);
                    }
                }

                @Override
                public void destroy(Object o)
                {
                    try
                    {
                        destroy.invoke(o);
                    }
                    catch (Throwable t)
                    {
                        throw new RuntimeException(t);
                    }
                }
            };
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()))
        {
            _decorator = asDecorator(event.getValue());
            if (_decorator == null)
                LOG.warn("Could not create decorator from {}={}", event.getName(), event.getValue());
            else
                _context.getObjectFactory().addDecorator(_decorator);
        }
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()) && _decorator != null)
        {
            _context.getObjectFactory().removeDecorator(_decorator);
        }
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent event)
    {
        if (_attributeName.equals(event.getName()))
        {
            if (_decorator != null)
                _context.getObjectFactory().removeDecorator(_decorator);
            attributeAdded(event);
        }
    }
}
