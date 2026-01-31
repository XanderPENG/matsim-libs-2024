"""
Author: Xander PENG
Date: 2026-01-30
File: figure_plot.py
Description: Provide some functions for figure plotting
"""
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import seaborn as sns
import numpy as np
from scipy import stats


def darken_color(color, factor=0.7):
    """
    Darken a color by a given factor.
    
    Args:
        color: Color in any matplotlib-accepted format
        factor: Factor to darken (0-1, smaller = darker)
    
    Returns:
        Darkened color as RGB tuple
    """
    rgb = mcolors.to_rgb(color)
    return tuple(c * factor for c in rgb)


def _fit_and_plot_curve(ax, data, x, bin_width, color, label, 
                        fitting_method='normal', n_components=2, kde_bw='scott'):
    """
    Internal helper to fit and plot distribution curves.
    
    Args:
        ax: Matplotlib axis
        data: 1D array of data values
        x: x-values for plotting the curve
        bin_width: Width of histogram bins (for scaling)
        color: Line color
        label: Label for legend
        fitting_method: 'normal', 'kde', or 'gmm'
        n_components: Number of Gaussian components for GMM (default: 2)
        kde_bw: Bandwidth for KDE ('scott', 'silverman', or float; smaller = more detail)
    """
    n = len(data)
    
    if fitting_method == 'normal':
        # Single Gaussian fit
        mu, std = stats.norm.fit(data)
        p = stats.norm.pdf(x, mu, std) * n * bin_width
        ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        
    elif fitting_method == 'kde':
        # Kernel Density Estimation - adapts to any distribution shape
        # kde_bw: smaller value = more detail/peaks; larger = smoother
        try:
            kde = stats.gaussian_kde(data, bw_method=kde_bw)
            p = kde(x) * n * bin_width
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        except Exception as e:
            print(f"KDE fitting failed: {e}")
            
    elif fitting_method == 'gmm':
        # Gaussian Mixture Model - good for bimodal/multimodal distributions
        # Plots a single combined curve (sum of all components)
        try:
            from sklearn.mixture import GaussianMixture
            
            data_reshaped = np.array(data).reshape(-1, 1)
            gmm = GaussianMixture(n_components=n_components, random_state=42)
            gmm.fit(data_reshaped)
            
            # Compute combined GMM PDF (single curve)
            x_reshaped = x.reshape(-1, 1)
            log_prob = gmm.score_samples(x_reshaped)
            p = np.exp(log_prob) * n * bin_width
            
            # Plot as single combined curve
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
                        
        except ImportError:
            print("sklearn not available. Install with: pip install scikit-learn")
            # Fallback to KDE
            kde = stats.gaussian_kde(data, bw_method='scott')
            p = kde(x) * n * bin_width
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        except Exception as e:
            print(f"GMM fitting failed: {e}")
    else:
        raise ValueError(f"Unknown fitting_method: {fitting_method}. Use 'normal', 'kde', or 'gmm'.")


def hist_plot(data_df,
              col1,
              col2=None,
              n_bins=50,
              figure_size=(10, 6),
              dpi=350,
              is_fitting=True,
              fitting_method='normal',
              n_components=2,
              kde_bw='scott',
              labels=None,
              figure_folder=None,
              filename=None,
              **kwargs
              ):
    """
    Plot histogram for the specified column(s) with optional distribution fitting.
    If col2 is provided, plot both columns together for comparison.

    Args:
        data_df: DataFrame containing the data
        col1: Name of the first column to plot
        col2: Name of the second column to plot (optional)
        n_bins: Number of bins for histogram (default: 50)
        figure_size: Figure size as tuple (width, height) (default: (10, 6))
        dpi: Figure resolution (default: 350)
        is_fitting: Whether to fit and plot distribution curves (default: True)
        fitting_method: Fitting method - 'normal', 'kde', or 'gmm' (default: 'normal')
            Can be a single string (applied to both columns) or a tuple (method1, method2)
            to use different methods for col1 and col2.
            - 'normal': Single Gaussian (for unimodal distributions)
            - 'kde': Kernel Density Estimation (non-parametric, adapts to any shape)
            - 'gmm': Gaussian Mixture Model (for bimodal/multimodal distributions)
        n_components: Number of Gaussian components for GMM fitting (default: 2)
            Can be a single int or tuple (n1, n2) for col1 and col2 separately.
        kde_bw: Bandwidth for KDE - 'scott', 'silverman', or float (default: 'scott')
            Smaller values (e.g., 0.1-0.3) = more detail/sharper peaks
            Larger values (e.g., 0.5-1.0) = smoother curve
            Can be a tuple (bw1, bw2) for col1 and col2 separately.
        labels: List of labels for legend [label1, label2] (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        
    Keyword Args:
        colors: Tuple of colors for (col1, col2) (default: ('steelblue', 'darkgrey'))
        alphas: Tuple of alpha values for (col1, col2) (default: (0.6, 0.8))
        xlabel: X-axis label (default: '')
        ylabel: Y-axis label (default: 'Density')
        label_size: Font size for axis labels (default: 10)
        hide_labels: Whether to hide axis labels (default: True)
        hide_legends: Whether to hide legends (default: False)
        show: Whether to display the plot (default: True)
        
    Examples:
        # Same method for both columns
        hist_plot(df, 'VKT', 'iter0_VKT', fitting_method='normal')
        
        # Different methods: GMM for col1 (bimodal), normal for col2 (unimodal)
        hist_plot(df, 'collab_rate', 'iter0_vkt', fitting_method=('gmm', 'normal'))
        
        # KDE with custom bandwidth (smaller = more detail)
        hist_plot(df, 'metric', fitting_method='kde', kde_bw=0.2)
    """
    # Extract kwargs with defaults
    colors = kwargs.get('colors', ('steelblue', 'darkgrey'))
    alphas = kwargs.get('alphas', (0.6, 0.8))
    xlabel = kwargs.get('xlabel', '')
    ylabel = kwargs.get('ylabel', 'Density')
    label_size = kwargs.get('label_size', 10)
    hide_labels = kwargs.get('hide_labels', True)
    show = kwargs.get('show', True)

    # Parse fitting_method - support tuple for separate control
    if isinstance(fitting_method, tuple):
        fitting_method1 = fitting_method[0]
        fitting_method2 = fitting_method[1] if len(fitting_method) > 1 else fitting_method[0]
    else:
        fitting_method1 = fitting_method2 = fitting_method

    # Parse n_components - support tuple for separate control
    if isinstance(n_components, tuple):
        n_components1 = n_components[0]
        n_components2 = n_components[1] if len(n_components) > 1 else n_components[0]
    else:
        n_components1 = n_components2 = n_components

    # Parse kde_bw - support tuple for separate control
    if isinstance(kde_bw, tuple):
        kde_bw1 = kde_bw[0]
        kde_bw2 = kde_bw[1] if len(kde_bw) > 1 else kde_bw[0]
    else:
        kde_bw1 = kde_bw2 = kde_bw

    # Validate columns
    if col1 not in data_df.columns:
        raise ValueError(f"Column '{col1}' not found in data_df.")
    if col2 is not None and col2 not in data_df.columns:
        raise ValueError(f"Column '{col2}' not found in data_df.")

    # Get data series (drop NaN values)
    data1 = data_df[col1].dropna()
    data2 = data_df[col2].dropna() if col2 is not None else None

    # Set labels
    if labels is None:
        label1 = col1
        label2 = col2 if col2 is not None else None
    else:
        label1 = labels[0]
        label2 = labels[1] if len(labels) > 1 and col2 is not None else None

    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)

    # Calculate bin range across all data
    if col2 is not None:
        min_val = min(data1.min(), data2.min())
        max_val = max(data1.max(), data2.max())
    else:
        min_val = data1.min()
        max_val = data1.max()

    bins = np.linspace(min_val, max_val, n_bins)
    bin_width = bins[1] - bins[0]

    # Plot histograms (col2 first if exists, so col1 is on top)
    if col2 is not None:
        plt.hist(data2, bins=bins, color=colors[1], alpha=alphas[1], label=label2)
    plt.hist(data1, bins=bins, color=colors[0], alpha=alphas[0], label=label1)

    # Fit and plot distribution curves
    if is_fitting:
        xmin, xmax = plt.xlim()
        x = np.linspace(xmin, xmax, 1000)

        # Fit col1
        _fit_and_plot_curve(ax, data1, x, bin_width, 
                           darken_color(colors[0], 0.5), label1,
                           fitting_method1, n_components1, kde_bw1)

        # Fit col2 if provided
        if col2 is not None:
            _fit_and_plot_curve(ax, data2, x, bin_width,
                               darken_color(colors[1]), label2,
                               fitting_method2, n_components2, kde_bw2)

    # Hide the right and top spines
    ax.spines["right"].set_visible(False)
    ax.spines["top"].set_visible(False)

    # Set labels
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight="bold")
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight="bold")
    if hide_labels:
        ax.set_xlabel('')
        ax.set_ylabel('')

    # Set tick font sizes
    plt.xticks(fontsize=10)
    plt.yticks(fontsize=10)

    # Add legend
    if not kwargs.get('hide_legends'):
        ax.legend()

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        plt.savefig(figure_folder + filename,
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()


    
